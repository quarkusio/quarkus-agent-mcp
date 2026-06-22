package io.quarkus.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.inject.Inject;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * MCP tools that proxy requests to a running Quarkus application's Dev MCP server.
 * Agents use searchTools to discover available tools, then callTool to invoke them.
 */
public class DevMcpProxyTools {

    private static final Logger LOG = Logger.getLogger(DevMcpProxyTools.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 2000;

    static final List<String> CATEGORY_ORDER = List.of(
            "web", "data", "security", "core", "messaging", "observability",
            "cloud", "reactive", "serialization", "compatibility", "ai", "alt-languages", "miscellaneous");

    private static final Set<String> ACRONYMS = Set.of("ai", "api", "cdi", "jpa", "ui");

    static final Map<String, String> DEFAULT_CATEGORIES = Map.ofEntries(
            Map.entry("quarkus-rest", "web"),
            Map.entry("quarkus-rest-client", "web"),
            Map.entry("quarkus-smallrye-openapi", "web"),
            Map.entry("quarkus-web-dependency-locator", "web"),
            Map.entry("quarkus-hibernate-orm", "data"),
            Map.entry("quarkus-hibernate-orm-panache", "data"),
            Map.entry("quarkus-hibernate-validator", "data"),
            Map.entry("quarkus-oidc", "security"),
            Map.entry("quarkus-security", "security"),
            Map.entry("quarkus-arc", "core"),
            Map.entry("quarkus-scheduler", "core"));

    private final AtomicLong requestId = new AtomicLong(0);

    // --- Startup / build polling ---
    private static final int STARTUP_WAIT_SECONDS = 120;
    private static final int POLL_INTERVAL_MS = 1000;
    private static final long BUILD_POLL_INTERVAL_MS = 3000;
    private static final long BUILD_WAIT_TIMEOUT_MS = 120000;

    @Inject
    QuarkusProcessManager processManager;

    @Inject
    ObjectMapper mapper;

    @Inject
    ContainerManager containerManager;

    @Inject
    WebClient webClient;

    @Inject
    SkillReader skillReader;

    @Inject
    LatestQuarkusVersionResolver versionResolver;

    @ConfigProperty(name = "agent-mcp.local-skills-dir")
    Optional<String> localSkillsDir;

    @ConfigProperty(name = "agent-mcp.skills.include-transitive", defaultValue = "false")
    boolean includeTransitiveDeps;

    @Tool(name = "quarkus_searchTools", description = "Discover available tools on the running Quarkus app's Dev MCP server. "
            + "Use this before interacting with the running app -- for testing, config, extensions, "
            + "endpoints, dev services, etc. Then use quarkus_callTool to invoke the discovered tool. "
            + "The tool list is DYNAMIC -- it changes when extensions are added or removed. "
            + "Re-call this after any extension change to discover newly available tools.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_searchTools", readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    Uni<ToolResponse> searchTools(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Search query to filter tools by name or description (case-insensitive). "
                    + "Examples: 'test' for testing tools, 'config' for configuration, "
                    + "'extension' for extension management. If omitted, returns all tools.", required = false) String query) {
        try {
            QuarkusInstance instance = resolveInstance(projectDir);
            int port = getDevMcpPort(instance);
            return fetchDevMcpTools(port, instance.getDevMcpPath())
                    .map(tools -> {
                        if (tools == null || !tools.isArray()) {
                            return ToolResponse.success("No tools available from Dev MCP");
                        }
                        List<JsonNode> matched = filterTools(tools, query);
                        if (matched.isEmpty()) {
                            return ToolResponse.success("No Dev MCP tools found matching: " + query);
                        }
                        try {
                            return ToolResponse.success(
                                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(matched));
                        } catch (JsonProcessingException e) {
                            return ToolResponse.error("Failed to serialize tools: " + e.getMessage());
                        }
                    })
                    .onFailure().recoverWithItem(e -> {
                        LOG.error("Failed to search Dev MCP tools for " + projectDir, e);
                        return ToolResponse.error(e.getMessage());
                    });
        } catch (Exception e) {
            LOG.error("Failed to search Dev MCP tools for " + projectDir, e);
            return Uni.createFrom().item(ToolResponse.error(e.getMessage()));
        }
    }

    @Tool(name = "quarkus_skills", description = "Get coding skills, patterns, testing guidelines, and configuration reference "
            + "for the Quarkus extensions used in the project. "
            + "ALWAYS call this BEFORE writing code or tests to learn the correct Quarkus patterns for each extension. "
            + "Does NOT require the app to be running -- reads from built extension JARs. "
            + "If the app is still building (just created), this will wait for the build to complete. "
            + "Skills may include an 'Available Dev MCP Tools' section listing extension-specific Dev MCP tools "
            + "that can be invoked via quarkus_callTool (e.g. OpenAPI schema retrieval, scheduler job management). "
            + "Some skills include module files (e.g., code patterns, reference tables) that can be loaded individually "
            + "using the 'module' parameter. The skill content lists available modules at the bottom. "
            + "Skills can be customized globally using quarkus_updateSkill (writes to ~/.quarkus/skills/). "
            + "Project-level skills in .agent/skills/ are standalone files readable by any agent -- "
            + "use quarkus_saveSkill to materialize a fully composed skill there, then edit directly.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_skills", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    Uni<ToolResponse> skills(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Optional query to filter skills by extension name (case-insensitive). "
                    + "Supports comma-separated names to fetch multiple skills at once "
                    + "(e.g., 'panache,rest,hibernate-validator'). "
                    + "If omitted, lists all available skills with their descriptions.", required = false) String query,
            @ToolArg(description = "Optional module path to load from a skill "
                    + "(e.g., 'modules/build.md', 'references/dependency-map.md'). "
                    + "Requires 'query' to identify which skill the module belongs to. "
                    + "Available module paths are listed in the skill's output.", required = false) String module) {
        Path effectiveLocalDir = localSkillsDir.map(Path::of).orElse(null);
        String queryLower = (query != null && !query.isBlank()) ? query.toLowerCase() : null;
        boolean needsModuleOnly = module != null && !module.isBlank();
        boolean needsContent = queryLower != null && !needsModuleOnly;

        return readSkillsOnWorker(projectDir, effectiveLocalDir, !needsContent && !needsModuleOnly,
                needsContent || needsModuleOnly)
                .chain(skills -> {
                    if (!skills.isEmpty()) {
                        return Uni.createFrom().item(skills);
                    }
                    return waitForBuildAndRetry(projectDir, !needsContent);
                })
                .map(skills -> {
                    try {
                        return buildSkillsResponse(skills, queryLower, query, module, needsContent,
                                effectiveLocalDir, projectDir);
                    } catch (Exception e) {
                        LOG.error("Failed to read skills for " + projectDir, e);
                        return ToolResponse.error("Failed to read skills: " + e.getMessage());
                    }
                })
                .onFailure().recoverWithItem(e -> {
                    LOG.error("Failed to read skills for " + projectDir, e);
                    return ToolResponse.error("Failed to read skills: " + e.getMessage());
                });
    }

    private Uni<List<SkillReader.SkillInfo>> readSkillsOnWorker(String projectDir, Path localDir,
            boolean metadataOnly, boolean loadModules) {
        return Uni.createFrom().item(() -> skillReader.readSkills(projectDir, localDir, metadataOnly,
                includeTransitiveDeps, loadModules))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private ToolResponse buildSkillsResponse(List<SkillReader.SkillInfo> skills, String queryLower,
            String query, String module, boolean needsContent, Path effectiveLocalDir, String projectDir) {
        if (skills.isEmpty()) {
            return ToolResponse.success(
                    "No extension skills found. Ensure the project has been built at least once "
                            + "and uses Quarkus extensions that provide skill files.");
        }

        List<SkillReader.SkillInfo> matched = skills;
        if (queryLower != null) {
            List<String> tokens = List.of(queryLower.split("[,\\s]+"));
            matched = skills.stream()
                    .filter(s -> {
                        String name = s.name().toLowerCase();
                        String desc = s.description() != null ? s.description().toLowerCase() : "";
                        return tokens.stream()
                                .anyMatch(t -> !t.isEmpty() && (name.contains(t) || desc.contains(t)));
                    })
                    .toList();
        }

        if (matched.isEmpty()) {
            return ToolResponse.success("No skills found matching: " + query);
        }

        if (module != null && !module.isBlank()) {
            if (matched.size() != 1) {
                return ToolResponse.error("Specify a single skill name in 'query' when loading a module. "
                        + "Matched: " + matched.stream().map(SkillReader.SkillInfo::name).toList());
            }
            SkillReader.SkillInfo skill = matched.get(0);
            if (skill.modules() == null || !skill.modules().containsKey(module)) {
                String available = skill.modules() != null
                        ? String.join(", ", skill.modules().keySet())
                        : "none";
                return ToolResponse.error("Module '" + module + "' not found in skill '" + skill.name()
                        + "'. Available modules: " + available);
            }
            return ToolResponse.success(skill.modules().get(module));
        }

        if (queryLower == null && matched.size() > 1) {
            return ToolResponse.success(formatSkillIndex(matched));
        }

        if (!needsContent) {
            matched = skillReader.readSkills(projectDir, effectiveLocalDir, false, includeTransitiveDeps);
        }

        String latestVersion = versionResolver.resolve(projectDir);

        StringBuilder sb = new StringBuilder();
        if (latestVersion != null) {
            sb.append("> **Latest Quarkus release:** ").append(latestVersion).append("\n\n");
        }
        boolean first = true;
        for (SkillReader.SkillInfo skill : matched) {
            if (!first) {
                sb.append("\n---\n\n");
            }
            first = false;
            sb.append("# ").append(skill.name()).append("\n\n");
            sb.append(skill.content());
            if (skill.modules() != null && !skill.modules().isEmpty()) {
                sb.append("\n\n---\n\n### Available Modules\n\n");
                sb.append("Load a module with `quarkus_skills query='").append(skill.name())
                        .append("' module='<path>'`:\n\n");
                for (String modulePath : skill.modules().keySet()) {
                    sb.append("- `").append(modulePath).append("`\n");
                }
            }
        }
        return ToolResponse.success(sb.toString());
    }

    /**
     * If the app is still building (STARTING state), poll with Mutiny until it reaches
     * RUNNING so that deployment JARs are available in the local Maven repository, then retry.
     */
    private Uni<List<SkillReader.SkillInfo>> waitForBuildAndRetry(String projectDir, boolean metadataOnly) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null || instance.getStatus() != QuarkusInstance.Status.STARTING) {
            return Uni.createFrom().item(List.of());
        }

        int maxPolls = (int) (BUILD_WAIT_TIMEOUT_MS / BUILD_POLL_INTERVAL_MS);
        return Uni.createFrom().item(instance::getStatus)
                .onItem().delayIt().by(Duration.ofMillis(BUILD_POLL_INTERVAL_MS))
                .repeat().whilst(status -> status == QuarkusInstance.Status.STARTING)
                .select().first(maxPolls)
                .collect().last()
                .chain(status -> {
                    if (status == QuarkusInstance.Status.CRASHED || status == QuarkusInstance.Status.STOPPED) {
                        return Uni.createFrom().item(List.of());
                    }
                    return readSkillsOnWorker(projectDir, localSkillsDir.map(Path::of).orElse(null),
                            metadataOnly, !metadataOnly);
                });
    }

    static String formatSkillIndex(List<SkillReader.SkillInfo> skills) {
        Map<String, List<SkillReader.SkillInfo>> grouped = new LinkedHashMap<>();
        for (SkillReader.SkillInfo skill : skills) {
            for (String category : resolveCategories(skill)) {
                grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(skill);
            }
        }

        // Known categories first in defined order, then any unknown alphabetically
        List<String> sortedCategories = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String cat : CATEGORY_ORDER) {
            if (grouped.containsKey(cat)) {
                sortedCategories.add(cat);
                added.add(cat);
            }
        }
        grouped.keySet().stream()
                .filter(c -> !added.contains(c))
                .sorted()
                .forEach(sortedCategories::add);

        StringBuilder sb = new StringBuilder();
        sb.append("Available extension skills (use query parameter to read a specific skill):\n");
        for (String category : sortedCategories) {
            sb.append("\n### ").append(titleCase(category)).append("\n");
            for (SkillReader.SkillInfo skill : grouped.get(category)) {
                sb.append("- **").append(skill.name()).append("**");
                if (skill.description() != null) {
                    sb.append(": ").append(skill.description());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    static List<String> resolveCategories(SkillReader.SkillInfo skill) {
        if (skill.categories() != null && !skill.categories().isEmpty()) {
            return skill.categories();
        }
        String defaultCat = DEFAULT_CATEGORIES.get(skill.name());
        return List.of(defaultCat != null ? defaultCat : "miscellaneous");
    }

    static String titleCase(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String[] parts = value.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("-");
            }
            if (ACRONYMS.contains(parts[i])) {
                sb.append(parts[i].toUpperCase());
            } else {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    @Tool(name = "quarkus_updateSkill", description = "Create or update a global skill customization for a Quarkus extension. "
            + "Writes to ~/.quarkus/skills/ (affects all projects). "
            + "Use this when the user wants to add personal conventions or guardrails to an extension skill. "
            + "IMPORTANT: Before writing, ask the user: "
            + "Should this ENHANCE the existing skill (append your content to the base) or OVERRIDE it (fully replace the base)? "
            + "Enhance is the default and recommended for most cases. "
            + "For project-level customization, use quarkus_saveSkill to materialize a skill into .agent/skills/, "
            + "then edit the file directly.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_updateSkill", readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    ToolResponse updateSkill(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "The extension skill name (e.g. 'quarkus-rest', 'quarkus-hibernate-orm-panache')") String skillName,
            @ToolArg(description = "The skill content in markdown (without frontmatter -- it will be generated)") String content,
            @ToolArg(description = "Optional description for the skill", required = false) String description,
            @ToolArg(description = "Optional comma-separated categories for the skill index (e.g. 'web', 'data', 'security', 'core')", required = false) String categories,
            @ToolArg(description = "Mode: 'enhance' (default) appends to the base skill, 'override' fully replaces it", required = false) String mode) {
        try {
            SkillReader.SkillMode skillMode = SkillReader.SkillMode.fromString(mode);

            List<String> parsedCategories = categories != null ? SkillReader.parseCategories(categories) : null;
            Path written = SkillReader.writeSkill(
                    skillName, content, description, parsedCategories, skillMode,
                    null, localSkillsDir.map(Path::of).orElse(null), false);

            String modeLabel = skillMode == SkillReader.SkillMode.ENHANCE ? "enhance" : "override";
            return ToolResponse.success(
                    "Skill '" + skillName + "' saved successfully.\n"
                            + "- **Mode**: " + modeLabel + "\n"
                            + "- **Scope**: global\n"
                            + "- **Path**: " + written + "\n\n"
                            + "The skill will take effect on the next call to `quarkus_skills`.");
        } catch (Exception e) {
            return ToolResponse.error("Failed to write skill: " + e.getMessage());
        }
    }

    @Tool(name = "quarkus_saveSkill", description = "Save/materialize a composed extension skill as a standalone file "
            + "in the project's .agent/skills/ directory. This creates a self-contained copy of the full skill "
            + "(including extension metadata and any global customizations) that any agent can read directly "
            + "from the filesystem. The user can then edit the file to customize it for the project. "
            + "Use this when the user wants to inspect, customize, or version-control an extension skill. "
            + "NOTE: If a local project skill already exists for this name, the tool will NOT overwrite it.",
            annotations = @Tool.Annotations(title = "quarkus_saveSkill", readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    ToolResponse saveSkill(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "The extension skill name to save locally "
                    + "(e.g. 'quarkus-rest', 'quarkus-hibernate-orm-panache')") String skillName) {
        try {
            Path effectiveLocalDir = localSkillsDir.map(Path::of).orElse(null);
            List<SkillReader.SkillInfo> skills = skillReader.readSkills(projectDir, effectiveLocalDir, false,
                    includeTransitiveDeps);
            SkillReader.SkillInfo matched = skills.stream()
                    .filter(s -> s.name().equalsIgnoreCase(skillName))
                    .findFirst()
                    .orElse(null);

            if (matched == null) {
                return ToolResponse.error(
                        "No extension skill found with name '" + skillName
                                + "'. Use quarkus_skills to see available skills.");
            }

            Path written = SkillReader.writeSkill(
                    matched.name(), matched.content(), matched.description(), matched.categories(),
                    SkillReader.SkillMode.OVERRIDE, projectDir, effectiveLocalDir, true, true);

            return ToolResponse.success(
                    "Skill '" + matched.name() + "' saved successfully.\n"
                            + "- **Mode**: override\n"
                            + "- **Path**: " + written + "\n\n"
                            + "You can now edit this file directly. "
                            + "The skill will take effect on the next call to `quarkus_skills`.");
        } catch (FileAlreadyExistsException e) {
            return ToolResponse.success(
                    "A local skill for '" + skillName + "' already exists at " + e.getFile() + ".\n"
                            + "To modify it, edit the file directly.");
        } catch (Exception e) {
            return ToolResponse.error("Failed to save skill: " + e.getMessage());
        }
    }

    @Tool(name = "quarkus_callTool", description = "Invoke a Dev MCP tool by name on the running Quarkus app. "
            + "Use quarkus_searchTools first to discover tool names and parameters. "
            + "After structural changes (adding extensions, endpoints), update README.md. "
            + "NEVER run 'mvn clean' or 'gradle clean' while dev mode is running -- it deletes target/test-classes and breaks the test runner. "
            + "If the test runner returns 'Tests already in progress' and won't recover, do a full quarkus_stop + quarkus_start cycle to reset it.")
    Uni<ToolResponse> callTool(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "The name of the Dev MCP tool to call (as returned by quarkus_searchTools)") String toolName,
            @ToolArg(description = "Arguments to pass to the tool as a JSON string (matching the tool's inputSchema). "
                    + "Omit if the tool takes no arguments.", required = false) String toolArguments) {
        try {
            QuarkusInstance instance = resolveInstance(projectDir);
            int port = getDevMcpPort(instance);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            if (toolArguments != null && !toolArguments.isBlank()) {
                params.put("arguments", mapper.readValue(toolArguments, Map.class));
            } else {
                params.put("arguments", Map.of());
            }

            return callDevMcp(port, instance.getDevMcpPath(), "tools/call", params)
                    .emitOn(Infrastructure.getDefaultWorkerPool())
                    .map(response -> {
                        try {
                            ToolResponse result = extractToolResult(response);

                            if (!result.isError() && toolName != null
                                    && (toolName.contains("extension") || toolName.contains("add")
                                            || toolName.contains("remove"))) {
                                DependencyResolver.invalidate(projectDir);

                                if (containerManager.isDefaultReady()) {
                                    String qVersion = QuarkusVersionDetector.detect(projectDir);
                                    Thread.ofVirtual().name("rag-incremental-load").start(() -> {
                                        try {
                                            containerManager.loadIncrementalRagData(qVersion, projectDir);
                                        } catch (Exception e) {
                                            LOG.debugf("Background incremental RAG load failed: %s",
                                                    e.getMessage());
                                        }
                                    });
                                }
                                String resultText = extractTextFromResult(result);
                                return ToolResponse.success(resultText
                                        + "\n\nREMINDER: Update README.md to reflect this change (features, extensions, guide links)."
                                        + "\nAlso write tests for any new functionality.");
                            }

                            return result;
                        } catch (JsonProcessingException e) {
                            return ToolResponse.error("Failed to parse Dev MCP response: " + e.getMessage());
                        }
                    })
                    .onFailure().recoverWithItem(e -> {
                        LOG.error("Failed to call Dev MCP tool '" + toolName + "' for " + projectDir, e);
                        return ToolResponse.error(e.getMessage());
                    });
        } catch (Exception e) {
            LOG.error("Failed to call Dev MCP tool '" + toolName + "' for " + projectDir, e);
            return Uni.createFrom().item(ToolResponse.error(e.getMessage()));
        }
    }

    private int getDevMcpPort(QuarkusInstance instance) {
        int port = instance.getHttpPort();
        if (port < 0) {
            throw new IllegalStateException("Could not detect HTTP port for the running Quarkus application.");
        }
        return port;
    }

    private QuarkusInstance resolveInstance(String projectDir) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null) {
            throw new IllegalStateException(
                    "Quarkus application is not running at: " + projectDir + ". Start it first with quarkus_start.");
        }

        if (instance.getStatus() == QuarkusInstance.Status.STARTING) {
            QuarkusInstance.Status status = awaitStartup(instance, STARTUP_WAIT_SECONDS * 1000L, POLL_INTERVAL_MS);
            if (status == null) {
                throw new IllegalStateException("Interrupted while waiting for Quarkus to start.");
            }
        }

        if (instance.getStatus() != QuarkusInstance.Status.RUNNING) {
            throw new IllegalStateException(
                    "Quarkus application is not running at: " + projectDir
                            + " (status: " + instance.getStatus() + "). Start it first with quarkus_start.");
        }
        return instance;
    }

    /**
     * Polls the instance status until it leaves STARTING or the deadline expires.
     * Returns the final observed status, or null if interrupted.
     */
    private QuarkusInstance.Status awaitStartup(QuarkusInstance instance, long timeoutMs, long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (instance.getStatus() == QuarkusInstance.Status.STARTING
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return instance.getStatus();
    }

    private List<JsonNode> filterTools(JsonNode tools, String query) {
        List<JsonNode> matched = new ArrayList<>();
        String queryLower = (query != null && !query.isBlank()) ? query.toLowerCase() : null;
        for (JsonNode tool : tools) {
            if (queryLower == null) {
                matched.add(tool);
            } else {
                String name = tool.has("name") ? tool.get("name").asText().toLowerCase() : "";
                String desc = tool.has("description") ? tool.get("description").asText().toLowerCase() : "";
                if (name.contains(queryLower) || desc.contains(queryLower)) {
                    matched.add(tool);
                }
            }
        }
        return matched;
    }

    private ToolResponse extractToolResult(JsonNode response) throws JsonProcessingException {
        if (response != null && response.has("content") && response.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode content : response.get("content")) {
                if (content.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(content.get("text").asText());
                }
            }
            if (response.has("isError") && response.get("isError").asBoolean()) {
                return ToolResponse.error(sb.toString());
            }
            return ToolResponse.success(sb.toString());
        }
        if (response != null) {
            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));
        }
        return ToolResponse.error("No response from Dev MCP");
    }

    private String extractTextFromResult(ToolResponse result) {
        if (result.content() != null && !result.content().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var c : result.content()) {
                if (c.type() == io.quarkiverse.mcp.server.Content.Type.TEXT) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(c.asText().text());
                }
            }
            return sb.toString();
        }
        return "";
    }

    private Uni<JsonNode> fetchDevMcpTools(int port, String devMcpPath) {
        return callDevMcp(port, devMcpPath, "tools/list", Map.of())
                .map(result -> result != null && result.has("tools") ? result.get("tools") : null);
    }

    private Uni<JsonNode> callDevMcp(int port, String devMcpPath, String method, Map<String, Object> params) {
        String jsonRpcRequest;
        try {
            jsonRpcRequest = mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId.incrementAndGet(),
                    "method", method,
                    "params", params));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(
                    new RuntimeException("Failed to serialize JSON-RPC request: " + e.getMessage(), e));
        }

        return webClient.postAbs("http://localhost:" + port + devMcpPath)
                .putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/json, text/event-stream")
                .timeout(REQUEST_TIMEOUT.toMillis())
                .sendBuffer(Buffer.buffer(jsonRpcRequest))
                // Retry only connection-level failures (before we get a response)
                .onFailure().retry()
                    .withBackOff(Duration.ofMillis(INITIAL_RETRY_DELAY_MS), Duration.ofSeconds(8))
                    .atMost(MAX_RETRIES)
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new DevMcpResponseException("Dev MCP returned HTTP " + response.statusCode());
                    }
                    try {
                        JsonNode body = mapper.readTree(response.bodyAsString());
                        if (body.has("result")) {
                            return body.get("result");
                        }
                        if (body.has("error")) {
                            String errorMsg = body.get("error").has("message")
                                    ? body.get("error").get("message").asText()
                                    : body.get("error").toString();
                            throw new DevMcpResponseException("Dev MCP error: " + errorMsg);
                        }
                        return (JsonNode) null;
                    } catch (JsonProcessingException e) {
                        throw new DevMcpResponseException("Failed to parse Dev MCP response", e);
                    }
                });
    }

    static class DevMcpResponseException extends RuntimeException {
        DevMcpResponseException(String message) {
            super(message);
        }

        DevMcpResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
