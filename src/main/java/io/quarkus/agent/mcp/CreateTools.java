package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tool for creating new Quarkus applications.
 * Uses the first available tool in this order:
 * 1. Quarkus CLI (quarkus create app)
 * 2. Maven (mvn io.quarkus.platform:quarkus-maven-plugin:create)
 * 3. JBang (jbang quarkus@quarkusio create app)
 */
public class CreateTools {

    private static final Logger LOG = Logger.getLogger(CreateTools.class);

    @Inject
    QuarkusProcessManager processManager;

    @Inject
    @ConfigProperty(name = "agent-mcp.default-quarkus-version")
    Optional<String> defaultQuarkusVersion;

    // Maven coordinate segments: letters, digits, dots, hyphens, underscores
    private static final Pattern VALID_MAVEN_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");

    // Extensions: comma-separated list of extension short names
    private static final Pattern VALID_EXTENSIONS = Pattern.compile("^[a-zA-Z0-9._,:-]+$");

    // Cache which command is available — doesn't change during the lifetime of the server
    private volatile String cachedCreateCommand;

    @Tool(name = "quarkus/create", description = "Create a new Quarkus application and auto-start it in dev mode. "
            + "RULES: 1) NEVER implement features manually when a Quarkus extension exists — "
            + "always search for and add the right extension first. "
            + "2) BEFORE creating the app or writing ANY code, present the user with extension choices for each capability. "
            + "When multiple extensions can fulfill a capability (e.g. REST: quarkus-rest vs resteasy-classic vs spring-web; "
            + "Web UI: Qute vs Web Bundler vs Quinoa vs Web Dependency Locator; "
            + "Persistence: Hibernate ORM with Panache vs Hibernate Reactive vs JDBC), "
            + "list ALL options with a recommended default and WAIT for the user to choose. NEVER silently pick one. "
            + "3) Use quarkus/skills for each chosen extension BEFORE writing any code — this is mandatory, not optional. "
            + "4) ALWAYS write tests for every feature if it makes sense and unless the user explicitly requested you not to. "
            + "5) Keep README.md updated with app description, features, endpoints, and Quarkus guide links after every change.")
    ToolResponse create(
            @ToolArg(description = "Absolute path to the directory where the project will be created. "
                    + "A subdirectory named after the artifactId will be created inside this directory.") String outputDir,
            @ToolArg(description = "The Maven groupId for the project (e.g. 'com.example')", required = false) String groupId,
            @ToolArg(description = "The Maven artifactId for the project (e.g. 'my-app').", required = false) String artifactId,
            @ToolArg(description = "Comma-separated list of Quarkus extensions to include "
                    + "(e.g. 'rest-jackson,hibernate-orm-panache,jdbc-postgresql')", required = false) String extensions,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (default: maven)", required = false) String buildTool,
            @ToolArg(description = "Quarkus platform version to use (e.g. '3.21.2', '999-SNAPSHOT'). "
                    + "If omitted, uses the latest release.", required = false) String quarkusVersion) {
        try {
            String resolvedGroupId = (groupId != null && !groupId.isBlank()) ? groupId : "org.acme";
            String resolvedArtifactId = (artifactId != null && !artifactId.isBlank()) ? artifactId : "quarkus-app";

            if (!VALID_MAVEN_ID.matcher(resolvedGroupId).matches()) {
                return ToolResponse.error("Invalid groupId: must contain only letters, digits, dots, hyphens, underscores.");
            }
            if (!VALID_MAVEN_ID.matcher(resolvedArtifactId).matches()) {
                return ToolResponse.error("Invalid artifactId: must contain only letters, digits, dots, hyphens, underscores.");
            }
            if (extensions != null && !extensions.isBlank() && !VALID_EXTENSIONS.matcher(extensions).matches()) {
                return ToolResponse.error("Invalid extensions: must contain only letters, digits, dots, hyphens, commas, colons.");
            }
            // Resolve version: explicit parameter > config property > latest (null)
            String resolvedVersion = (quarkusVersion != null && !quarkusVersion.isBlank())
                    ? quarkusVersion
                    : defaultQuarkusVersion.filter(v -> !v.isBlank()).orElse(null);

            if (resolvedVersion != null && !VALID_MAVEN_ID.matcher(resolvedVersion).matches()) {
                return ToolResponse.error("Invalid quarkusVersion: must contain only letters, digits, dots, hyphens, underscores.");
            }

            File outDir = new File(outputDir);
            if (!outDir.isDirectory()) {
                return ToolResponse.error("Output directory does not exist: " + outputDir);
            }

            List<String> command = buildCommand(outDir, resolvedGroupId, resolvedArtifactId, extensions, buildTool,
                    resolvedVersion);
            LOG.infof("Creating Quarkus app: %s", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(outDir)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            int exitCode;
            try {
                output = captureOutput(process);
                exitCode = process.waitFor();
            } finally {
                process.destroyForcibly();
            }

            if (exitCode != 0) {
                return ToolResponse.error("Project creation failed (exit " + exitCode + "):\n" + output);
            }

            String projectDir = new File(outDir, resolvedArtifactId).getAbsolutePath();

            // Ensure rest-assured is available for testing (--no-code skips it)
            addRestAssuredIfMissing(projectDir);

            // Ensure source directories exist so dev mode watches them from the start.
            // --no-code creates an empty project; if src/test/java doesn't exist when
            // dev mode starts, the file watcher never registers it and tests added later
            // won't be discovered without a full restart.
            createSourceDirectories(projectDir);

            // Generate AGENTS.md with Quarkus-specific instructions (and CLAUDE.md pointing to it)
            generateProjectInstructions(projectDir, extensions);

            // Auto-start the app in dev mode
            try {
                processManager.start(projectDir, buildTool);
                LOG.infof("Auto-started Quarkus app at: %s", projectDir);
                return ToolResponse.success("Quarkus project created and starting in dev mode at: " + projectDir
                        + "\n\nNEXT STEPS (follow this order strictly):"
                        + "\n1. STOP — do NOT write any code yet. For each capability the user requested, "
                        + "search for Quarkus extensions that provide it using quarkus/searchDocs. "
                        + "NEVER roll your own solution when an extension exists."
                        + "\n2. PRESENT OPTIONS — when multiple extensions can fulfill a capability, "
                        + "list ALL options to the user with a recommended default marked. "
                        + "Wait for the user to choose before proceeding. Never silently pick one."
                        + "\n3. LOAD SKILLS — call quarkus/skills for each chosen extension BEFORE writing any code. "
                        + "This is mandatory, not optional."
                        + "\n4. Add chosen extensions via quarkus/searchTools query='extension' → quarkus/callTool."
                        + "\n5. Use quarkus/searchDocs to look up additional Quarkus APIs and best practices."
                        + "\n6. Write your code AND tests. Always include tests for every feature."
                        + "\n7. Run tests with quarkus/callTool: use 'devui-testing_runTests' to run all tests, "
                        + "'devui-testing_runAffectedTests' to run only tests affected by your changes, "
                        + "or 'devui-testing_runTest' with arguments {\"className\":\"com.example.MyTest\"} for a specific test."
                        + "\n8. After code changes, trigger a reload via quarkus/callTool with toolName 'devui-logstream_forceRestart'. Do NOT restart the app manually."
                        + "\n   IMPORTANT: After pom.xml/build.gradle changes (adding dependencies or extensions), you MUST do a full quarkus/stop + quarkus/start. forceRestart only recompiles source — it does NOT re-resolve dependencies."
                        + "\n9. Update README.md with: app description, features, endpoints, how to run, and links to Quarkus guides."
                        + "\n10. After core features work, suggest to the user: security, observability, health checks, OpenAPI.");
            } catch (Exception startError) {
                LOG.warnf("Project created but failed to auto-start: %s", startError.getMessage());
                return ToolResponse.success("Quarkus project created at: " + projectDir
                        + "\nAuto-start failed: " + startError.getMessage()
                        + "\nUse quarkus/start with projectDir='" + projectDir + "' to start it manually.");
            }
        } catch (Exception e) {
            LOG.error("Failed to create Quarkus project", e);
            return ToolResponse.error("Failed to create project: " + e.getMessage());
        }
    }

    private List<String> buildCommand(File outputDir, String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        String cmd = resolveCreateCommand();
        return switch (cmd) {
            case "quarkus" -> buildQuarkusCliCommand("quarkus", groupId, artifactId, extensions, buildTool,
                    quarkusVersion);
            case "mvn" -> buildMavenCommand(groupId, artifactId, extensions, buildTool, quarkusVersion);
            case "jbang" -> buildJBangCommand(groupId, artifactId, extensions, buildTool, quarkusVersion);
            default -> throw new IllegalStateException("Unexpected command: " + cmd);
        };
    }

    private String resolveCreateCommand() {
        if (cachedCreateCommand != null) {
            return cachedCreateCommand;
        }
        if (isCommandAvailable("quarkus")) {
            LOG.info("Using Quarkus CLI to create projects");
            cachedCreateCommand = "quarkus";
        } else if (isCommandAvailable("mvn")) {
            LOG.info("Quarkus CLI not found, using Maven plugin");
            cachedCreateCommand = "mvn";
        } else if (isCommandAvailable("jbang")) {
            LOG.info("Neither Quarkus CLI nor Maven found, using JBang");
            cachedCreateCommand = "jbang";
        } else {
            throw new IllegalStateException(
                    "No tool found to create Quarkus projects. Install one of: "
                            + "Quarkus CLI (https://quarkus.io/guides/cli-tooling), "
                            + "Maven (https://maven.apache.org), or "
                            + "JBang (https://jbang.dev).");
        }
        return cachedCreateCommand;
    }

    private List<String> buildQuarkusCliCommand(String quarkusCmd, String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        List<String> cmd = new ArrayList<>();
        cmd.add(quarkusCmd);
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        cmd.add("--no-code");
        cmd.add("--batch-mode");

        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            cmd.add("--platform-bom=io.quarkus:quarkus-bom:" + quarkusVersion);
        }
        if (extensions != null && !extensions.isBlank()) {
            cmd.add("--extension=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("--gradle");
        }

        return cmd;
    }

    private List<String> buildJBangCommand(String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        List<String> cmd = new ArrayList<>();
        cmd.add("jbang");
        cmd.add("quarkus@quarkusio");
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        cmd.add("--no-code");
        cmd.add("--batch-mode");

        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            cmd.add("--platform-bom=io.quarkus:quarkus-bom:" + quarkusVersion);
        }
        if (extensions != null && !extensions.isBlank()) {
            cmd.add("--extension=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("--gradle");
        }

        return cmd;
    }

    private List<String> buildMavenCommand(String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        String pluginGroupId = "io.quarkus.platform";
        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            pluginGroupId = "io.quarkus";
        }
        cmd.add(pluginGroupId + ":quarkus-maven-plugin:"
                + (quarkusVersion != null && !quarkusVersion.isBlank() ? quarkusVersion + ":" : "")
                + "create");
        cmd.add("-DprojectGroupId=" + groupId);
        cmd.add("-DprojectArtifactId=" + artifactId);
        cmd.add("-DnoCode=true");
        cmd.add("-B");

        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            cmd.add("-DplatformGroupId=io.quarkus");
            cmd.add("-DplatformVersion=" + quarkusVersion);
        }
        if (extensions != null && !extensions.isBlank()) {
            cmd.add("-Dextensions=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("-DbuildTool=gradle");
        }

        return cmd;
    }

    private boolean isCommandAvailable(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    private String captureOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                LOG.debug(line);
            }
            return sb.toString().trim();
        }
    }

    private void createSourceDirectories(String projectDir) {
        Path base = Path.of(projectDir);
        try {
            Files.createDirectories(base.resolve("src/main/java"));
            Files.createDirectories(base.resolve("src/main/resources"));
            Files.createDirectories(base.resolve("src/test/java"));
            Files.createDirectories(base.resolve("src/test/resources"));
            LOG.debugf("Ensured source directories exist in %s", projectDir);
        } catch (IOException e) {
            LOG.debugf("Failed to create source directories: %s", e.getMessage());
        }
    }

    private void addRestAssuredIfMissing(String projectDir) {
        Path pomPath = Path.of(projectDir, "pom.xml");
        Path gradleKtsPath = Path.of(projectDir, "build.gradle.kts");
        Path gradlePath = Path.of(projectDir, "build.gradle");

        if (Files.exists(pomPath)) {
            addRestAssuredToMaven(pomPath);
        } else if (Files.exists(gradleKtsPath)) {
            addRestAssuredToGradle(gradleKtsPath, "testImplementation(\"io.rest-assured:rest-assured\")");
        } else if (Files.exists(gradlePath)) {
            addRestAssuredToGradle(gradlePath, "testImplementation 'io.rest-assured:rest-assured'");
        }
    }

    // Safe for freshly-generated POMs from Quarkus CLI where the structure is predictable.
    private void addRestAssuredToMaven(Path pomPath) {
        try {
            String pom = Files.readString(pomPath, StandardCharsets.UTF_8);
            if (pom.contains("rest-assured")) {
                return;
            }
            String restAssuredDep = """
                        <dependency>
                            <groupId>io.rest-assured</groupId>
                            <artifactId>rest-assured</artifactId>
                            <scope>test</scope>
                        </dependency>
                    """;
            int insertPoint = pom.lastIndexOf("</dependencies>");
            if (insertPoint > 0) {
                pom = pom.substring(0, insertPoint) + restAssuredDep + pom.substring(insertPoint);
                Files.writeString(pomPath, pom, StandardCharsets.UTF_8);
                LOG.debugf("Added rest-assured test dependency to %s", pomPath);
            }
        } catch (IOException e) {
            LOG.debugf("Failed to add rest-assured dependency: %s", e.getMessage());
        }
    }

    private void addRestAssuredToGradle(Path buildFile, String dependency) {
        try {
            String content = Files.readString(buildFile, StandardCharsets.UTF_8);
            if (content.contains("rest-assured")) {
                return;
            }
            int depsStart = content.indexOf("dependencies");
            if (depsStart < 0) {
                return;
            }
            int braceStart = content.indexOf('{', depsStart);
            if (braceStart < 0) {
                return;
            }
            // Find the matching closing brace (safe for freshly-generated Quarkus projects)
            int depth = 0;
            int closingBrace = -1;
            for (int i = braceStart; i < content.length(); i++) {
                if (content.charAt(i) == '{') {
                    depth++;
                } else if (content.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        closingBrace = i;
                        break;
                    }
                }
            }
            if (closingBrace < 0) {
                return;
            }
            content = content.substring(0, closingBrace)
                    + "    " + dependency + "\n"
                    + content.substring(closingBrace);
            Files.writeString(buildFile, content, StandardCharsets.UTF_8);
            LOG.debugf("Added rest-assured test dependency to %s", buildFile);
        } catch (IOException e) {
            LOG.debugf("Failed to add rest-assured dependency: %s", e.getMessage());
        }
    }

    private void generateProjectInstructions(String projectDir, String extensions) {
        try {
            String agentsMdContent = """
                    # AGENTS.md — Quarkus Project Instructions

                    This is a Quarkus application. Follow these rules when working on this project.

                    ## CRITICAL — Extension-First Rule (NEVER skip this)

                    **STOP before writing ANY code.** For every feature or capability the user requests:

                    1. **Search for Quarkus extensions** that provide the capability using `quarkus/searchDocs` and `quarkus/searchTools query='extension'`.
                    2. **Present ALL matching options to the user** with a recommended default marked. Examples:
                       - User asks for REST → present: **quarkus-rest** (recommended), resteasy-classic, spring-web
                       - User asks for web UI → present: **Qute** (recommended), Web Bundler, Quinoa, Web Dependency Locator
                       - User asks for persistence → present: **Hibernate ORM with Panache** (recommended), Hibernate Reactive, JDBC directly
                       - User asks for security → present: **OIDC** (recommended), Security JDBC, Security JPA, Security Properties
                    3. **Wait for the user to choose** before proceeding. Do NOT silently pick an extension.
                    4. **Load skills** with `quarkus/skills` for the chosen extension BEFORE writing any code.

                    Skipping any of these steps is a violation. NEVER implement a feature by hand-coding HTML, JavaScript, REST endpoints, or other functionality when a Quarkus extension exists for it.

                    ## Required Workflow

                    1. **Use quarkus/update (via subagent) when returning to this project** — checks if the Quarkus version is up-to-date and suggests upgrades.
                    2. **Use quarkus/skills BEFORE writing any code or tests** — it contains extension-specific patterns, testing approaches, and common pitfalls that prevent mistakes. Skills may also list **Available Dev MCP Tools** specific to each extension (e.g. OpenAPI schema retrieval, scheduler job management) — use these via `quarkus/callTool`. Call this EVERY time you are about to add or modify a feature, not just at project creation.
                    3. **Use quarkus/searchDocs for Quarkus documentation** — do NOT use generic documentation tools (Context7, web search). The Quarkus doc search is version-aware and more accurate.
                    4. **Use quarkus/searchTools to discover Dev MCP tools** on the running app for testing, config changes, and extension management. The tool list is **dynamic** — it changes when extensions are added or removed. Re-call `quarkus/searchTools` after any extension change to discover newly available tools. Note: some extension-specific tools are also documented in the skills output (see step 2).
                    5. **Use quarkus/callTool to invoke Dev MCP tools** — run tests, add extensions, update configuration. Do NOT run Maven/Gradle commands manually.
                    6. **After code changes, trigger a reload** via `quarkus/callTool` with toolName `devui-logstream_forceRestart`. Do NOT restart the app manually.
                    7. **After pom.xml / build.gradle changes** (adding dependencies or extensions), you MUST do a full `quarkus/stop` + `quarkus/start` cycle. A `forceRestart` only recompiles source files — it does NOT re-resolve dependencies.

                    ## Rules

                    - NEVER implement features manually when a Quarkus extension exists — search for and add the right extension first.
                    - NEVER silently pick an extension when multiple options exist — ALWAYS present options to the user and wait for their choice.
                    - NEVER write code for a feature without first loading its skill via `quarkus/skills`.
                    - ALWAYS write tests for every feature — no exceptions.
                    - ALWAYS keep README.md updated with app description, features, endpoints, and Quarkus guide links.
                    - ALWAYS summarize after completing work — when you finish building an app, adding a feature, or completing a task, provide a clear summary of what was done (files created/modified, endpoints added, extensions used, etc.) and suggest logical next steps the user might want to take (e.g. adding security, observability, persistence, testing improvements, deployment).
                    - Use `@QuarkusTest` for integration tests — Dev Services auto-starts backing services (databases, messaging, etc.).
                    - Use `%dev.` and `%test.` profile prefixes for dev/test configuration — never hardcode connection URLs without a profile prefix.

                    ## Testing

                    ALWAYS run tests using a **subagent** so the main conversation stays responsive:

                    ```
                    Use the Agent tool to launch a subagent with this prompt:
                      "Run the Quarkus tests for project <projectDir> using quarkus/callTool
                       with toolName 'devui-testing_runTests'. Analyze the results and report
                       which tests passed, failed, or errored. If tests fail, include the
                       failure messages and suggest fixes."
                    ```

                    - Use `devui-testing_runTests` to run all tests.
                    - Use `devui-testing_runTest` with arguments `{"className":"com.example.MyTest"}` to run a specific test class.
                    - Do NOT run Maven/Gradle test commands manually — the Dev MCP test tools handle compilation, hot reload, and result reporting.
                    - After fixing test failures, re-run tests with a subagent to verify the fix.

                    ## Error Handling

                    When something goes wrong (compilation error, deployment failure, runtime exception):

                    1. Use `quarkus/callTool` with toolName `devui-exceptions_getLastException` to get structured exception details (class, message, stack trace, user code location).
                    2. Fix the issue based on the exception details.
                    3. Call `devui-exceptions_clearLastException` to clear the recorded exception.
                    4. Use `quarkus/logs` only when you need broader log context beyond the exception itself.

                    **Note:** If the app fails on its very first deploy (before the Dev MCP handler is registered), the exception endpoint won't exist yet — fall back to `quarkus/logs` in that case. For hot-reload failures (the common case), the endpoint is always available from the prior successful deploy.

                    ## Customizing Skills

                    Extension skills can be overridden per-project by placing SKILL.md files under
                    `.quarkus/skills/<extension-name>/SKILL.md`. Project-level
                    skills take precedence over the built-in defaults. This is useful for enforcing
                    team conventions or adjusting patterns for specific project requirements.
                    """;
            Files.writeString(Path.of(projectDir, "AGENTS.md"), agentsMdContent, StandardCharsets.UTF_8);
            LOG.debugf("Generated AGENTS.md in %s", projectDir);

            // Generate CLAUDE.md that points to AGENTS.md for Claude Code compatibility
            String claudeMdContent = """
                    See [AGENTS.md](AGENTS.md) for project instructions.
                    """;
            Files.writeString(Path.of(projectDir, "CLAUDE.md"), claudeMdContent, StandardCharsets.UTF_8);
            LOG.debugf("Generated CLAUDE.md in %s", projectDir);
        } catch (IOException e) {
            LOG.debugf("Failed to generate AGENTS.md: %s", e.getMessage());
        }
    }
}
