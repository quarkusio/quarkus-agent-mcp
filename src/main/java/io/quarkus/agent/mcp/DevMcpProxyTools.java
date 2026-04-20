package io.quarkus.agent.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;

/**
 * MCP tools that proxy requests to a running Quarkus application's Dev MCP server.
 * Agents use searchTools to discover available tools, then callTool to invoke them.
 */
public class DevMcpProxyTools {

    private static final Logger LOG = Logger.getLogger(DevMcpProxyTools.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

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

    @ConfigProperty(name = "agent-mcp.local-skills-dir")
    Optional<String> localSkillsDir;

    @Tool(name = "quarkus_searchTools", description = "Discover available tools on the running Quarkus app's Dev MCP server. "
            + "Use this before interacting with the running app -- for testing, config, extensions, "
            + "endpoints, dev services, etc. Then use quarkus_callTool to invoke the discovered tool. "
            + "The tool list is DYNAMIC -- it changes when extensions are added or removed. "
            + "Re-call this after any extension change to discover newly available tools.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_searchTools", readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResponse searchTools(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Search query to filter tools by name or description (case-insensitive). "
                    + "Examples: 'test' for testing tools, 'config' for configuration, "
                    + "'extension' for extension management. If omitted, returns all tools.", required = false) String query) {
        try {
            int port = resolvePort(projectDir);
            JsonNode tools = fetchDevMcpTools(port);
            if (tools == null || !tools.isArray()) {
                return ToolResponse.success("No tools available from Dev MCP");
            }

            List<JsonNode> matched = filterTools(tools, query);
            if (matched.isEmpty()) {
                return ToolResponse.success("No Dev MCP tools found matching: " + query);
            }

            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(matched));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize tools: " + e.getMessage());
        } catch (Exception e) {
            LOG.error("Failed to search Dev MCP tools for " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_skills", description = "Get coding skills, patterns, testing guidelines, and configuration reference "
            + "for the Quarkus extensions used in the project. "
            + "ALWAYS call this BEFORE writing code or tests to learn the correct Quarkus patterns for each extension. "
            + "Does NOT require the app to be running -- reads from built extension JARs. "
            + "If the app is still building (just created), this will wait for the build to complete. "
            + "Skills may include an 'Available Dev MCP Tools' section listing extension-specific Dev MCP tools "
            + "that can be invoked via quarkus_callTool (e.g. OpenAPI schema retrieval, scheduler job management). "
            + "Skills can be customized using quarkus_updateSkill. Customizations use a three-layer chain: "
            + "JAR defaults -> global (~/.quarkus/skills/) -> project (.quarkus/skills/). "
            + "By default, customizations ENHANCE (append to) the base skill. "
            + "Use mode 'override' to fully replace a base skill.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_skills", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    ToolResponse skills(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Optional query to filter skills by extension name (case-insensitive). "
                    + "Examples: 'panache', 'rest', 'security', 'kafka'. "
                    + "If omitted, lists all available skills with their descriptions.", required = false) String query) {
        try {
            Path effectiveLocalDir = localSkillsDir.map(Path::of).orElse(null);
            String queryLower = (query != null && !query.isBlank()) ? query.toLowerCase() : null;
            boolean needsContent = queryLower != null;

            // When a query is provided we need full content; otherwise read metadata only
            List<SkillReader.SkillInfo> skills = SkillReader.readSkills(projectDir, effectiveLocalDir, !needsContent);

            // If no skills found, check if the app is still building and wait for it
            if (skills.isEmpty()) {
                skills = waitForBuildAndRetry(projectDir, !needsContent);
            }

            if (skills.isEmpty()) {
                return ToolResponse.success(
                        "No extension skills found. Ensure the project has been built at least once "
                                + "and uses Quarkus extensions that provide skill files.");
            }

            // Filter by query if provided
            List<SkillReader.SkillInfo> matched = skills;
            if (queryLower != null) {
                matched = skills.stream()
                        .filter(s -> s.name().toLowerCase().contains(queryLower)
                                || (s.description() != null && s.description().toLowerCase().contains(queryLower)))
                        .toList();
            }

            if (matched.isEmpty()) {
                return ToolResponse.success("No skills found matching: " + query);
            }

            // Multiple skills and no query — return summary list (no content needed)
            if (queryLower == null && matched.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("Available extension skills (use query parameter to read a specific skill):\n\n");
                for (SkillReader.SkillInfo skill : matched) {
                    sb.append("- **").append(skill.name()).append("**");
                    if (skill.description() != null) {
                        sb.append(": ").append(skill.description());
                    }
                    sb.append("\n");
                }
                return ToolResponse.success(sb.toString());
            }

            // Single skill without query — we only read metadata, so re-read with content
            if (!needsContent) {
                skills = SkillReader.readSkills(projectDir, effectiveLocalDir, false);
                matched = skills;
            }

            // Return full content for matched skills
            StringBuilder sb = new StringBuilder();
            for (SkillReader.SkillInfo skill : matched) {
                if (!sb.isEmpty()) {
                    sb.append("\n---\n\n");
                }
                sb.append("# ").append(skill.name()).append("\n\n");
                sb.append(skill.content());
            }
            return ToolResponse.success(sb.toString());
        } catch (Exception e) {
            LOG.error("Failed to read skills for " + projectDir, e);
            return ToolResponse.error("Failed to read skills: " + e.getMessage());
        }
    }

    /**
     * If the app is still building (STARTING state), wait for it to reach RUNNING
     * so that deployment JARs are available in the local Maven repository, then retry.
     */
    private List<SkillReader.SkillInfo> waitForBuildAndRetry(String projectDir, boolean metadataOnly) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null || instance.getStatus() != QuarkusInstance.Status.STARTING) {
            return List.of();
        }

        QuarkusInstance.Status status = awaitStartup(instance, BUILD_WAIT_TIMEOUT_MS, BUILD_POLL_INTERVAL_MS);
        if (status == QuarkusInstance.Status.CRASHED || status == QuarkusInstance.Status.STOPPED) {
            return List.of();
        }
        // RUNNING or timed out (still STARTING) -- try reading skills either way
        return SkillReader.readSkills(projectDir, localSkillsDir.map(Path::of).orElse(null), metadataOnly);
    }

    @Tool(name = "quarkus_updateSkill", description = "Create or update a skill customization for a Quarkus extension. "
            + "Use this when the user wants to add project conventions, team standards, or guardrails to an extension skill. "
            + "IMPORTANT: Before writing, ask the user two questions: "
            + "(1) Should this ENHANCE the existing skill (append your content to the base) or OVERRIDE it (fully replace the base)? "
            + "Enhance is the default and recommended for most cases. "
            + "(2) Should this be saved at PROJECT scope (.quarkus/skills/ in the project, affects only this project) "
            + "or GLOBAL scope (~/.quarkus/skills/, affects all projects)?",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_updateSkill", readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    ToolResponse updateSkill(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "The extension skill name (e.g. 'quarkus-rest', 'quarkus-hibernate-orm-panache')") String skillName,
            @ToolArg(description = "The skill content in markdown (without frontmatter -- it will be generated)") String content,
            @ToolArg(description = "Optional description for the skill", required = false) String description,
            @ToolArg(description = "Mode: 'enhance' (default) appends to the base skill, 'override' fully replaces it", required = false) String mode,
            @ToolArg(description = "Scope: 'project' (default) saves under .quarkus/skills/ in the project, "
                    + "'global' saves under ~/.quarkus/skills/", required = false) String scope) {
        try {
            SkillReader.SkillMode skillMode = SkillReader.SkillMode.fromString(mode);
            boolean projectScope = !"global".equalsIgnoreCase(scope);

            Path written = SkillReader.writeSkill(
                    skillName, content, description, skillMode,
                    projectDir, localSkillsDir.map(Path::of).orElse(null), projectScope);

            String modeLabel = skillMode == SkillReader.SkillMode.ENHANCE ? "enhance" : "override";
            String scopeLabel = projectScope ? "project" : "global";
            return ToolResponse.success(
                    "Skill '" + skillName + "' saved successfully.\n"
                            + "- **Mode**: " + modeLabel + "\n"
                            + "- **Scope**: " + scopeLabel + "\n"
                            + "- **Path**: " + written + "\n\n"
                            + "The skill will take effect on the next call to `quarkus_skills`.");
        } catch (Exception e) {
            return ToolResponse.error("Failed to write skill: " + e.getMessage());
        }
    }

    @Tool(name = "quarkus_callTool", description = "Invoke a Dev MCP tool by name on the running Quarkus app. "
            + "Use quarkus_searchTools first to discover tool names and parameters. "
            + "After structural changes (adding extensions, endpoints), update README.md.")
    ToolResponse callTool(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "The name of the Dev MCP tool to call (as returned by quarkus_searchTools)") String toolName,
            @ToolArg(description = "Arguments to pass to the tool as a JSON string (matching the tool's inputSchema). "
                    + "Omit if the tool takes no arguments.", required = false) String toolArguments) {
        try {
            int port = resolvePort(projectDir);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            if (toolArguments != null && !toolArguments.isBlank()) {
                params.put("arguments", mapper.readValue(toolArguments, Map.class));
            } else {
                params.put("arguments", Map.of());
            }

            JsonNode response = callDevMcp(port, "tools/call", params);
            ToolResponse result = extractToolResult(response);

            // Remind agent to update README after structural changes
            if (!result.isError() && toolName != null
                    && (toolName.contains("extension") || toolName.contains("add")
                            || toolName.contains("remove"))) {
                String resultText = extractTextFromResult(result);
                return ToolResponse.success(resultText
                        + "\n\nREMINDER: Update README.md to reflect this change (features, extensions, guide links)."
                        + "\nAlso write tests for any new functionality.");
            }

            return result;
        } catch (Exception e) {
            LOG.error("Failed to call Dev MCP tool '" + toolName + "' for " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    private int resolvePort(String projectDir) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null) {
            throw new IllegalStateException(
                    "Quarkus application is not running at: " + projectDir + ". Start it first with quarkus_start.");
        }

        // If the app is still starting, wait for it to become ready
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

        int port = instance.getHttpPort();
        if (port < 0) {
            throw new IllegalStateException("Could not detect HTTP port for the running Quarkus application.");
        }
        return port;
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

    private JsonNode fetchDevMcpTools(int port) {
        JsonNode result = callDevMcp(port, "tools/list", Map.of());
        if (result != null && result.has("tools")) {
            return result.get("tools");
        }
        return null;
    }

    private JsonNode callDevMcp(int port, String method, Map<String, Object> params) {
        try {
            String jsonRpcRequest = mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId.incrementAndGet(),
                    "method", method,
                    "params", params));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/q/dev-mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> response = HttpClientProvider.getHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Dev MCP returned HTTP " + response.statusCode());
            }

            JsonNode body = mapper.readTree(response.body());
            if (body.has("result")) {
                return body.get("result");
            }
            if (body.has("error")) {
                String errorMsg = body.get("error").has("message")
                        ? body.get("error").get("message").asText()
                        : body.get("error").toString();
                throw new RuntimeException("Dev MCP error: " + errorMsg);
            }
            return null;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call Dev MCP: " + e.getMessage(), e);
        }
    }
}
