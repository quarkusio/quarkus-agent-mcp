package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
    SkillReader skillReader;

    @Inject
    @ConfigProperty(name = "agent-mcp.default-quarkus-version")
    Optional<String> defaultQuarkusVersion;

    // Maven coordinate segments: letters, digits, dots, hyphens, underscores
    private static final Pattern VALID_MAVEN_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");

    // Extensions: comma-separated list of extension short names
    private static final Pattern VALID_EXTENSIONS = Pattern.compile("^[a-zA-Z0-9._,:-]+$");

    // Cache which command is available -- doesn't change during the lifetime of the server
    private volatile String cachedCreateCommand;

    @Tool(name = "quarkus_create", description = "Create a new Quarkus application and auto-start it in dev mode. "
            + "RULES: 1) NEVER implement features manually when a Quarkus extension exists -- "
            + "always search for and add the right extension first. "
            + "2) BEFORE creating the app or writing ANY code, use quarkus_searchTools query='extension' and "
            + "quarkus_searchDocs to discover ALL extensions that can fulfill each requested capability. "
            + "Present the full list of matching extensions to the user with a recommended default "
            + "and WAIT for the user to choose. NEVER silently pick one. "
            + "3) Use quarkus_skills for each chosen extension BEFORE writing any code -- this is mandatory, not optional. "
            + "4) ALWAYS write tests for every feature if it makes sense and unless the user explicitly requested you not to. "
            + "5) Keep README.md updated with app description, features, endpoints, and Quarkus guide links after every change.")
    ToolResponse create(
            @ToolArg(description = "Absolute path to the directory where the project will be created. "
                    + "By default, a subdirectory named after the artifactId will be created inside this directory. "
                    + "If the directory name matches the artifactId and the directory is empty, "
                    + "the project is created directly in this directory instead.") String outputDir,
            @ToolArg(description = "The Maven groupId for the project (e.g. 'com.example')", required = false) String groupId,
            @ToolArg(description = "The Maven artifactId for the project (e.g. 'my-app').", required = false) String artifactId,
            @ToolArg(description = "Comma-separated list of Quarkus extensions to include "
                    + "(e.g. 'rest-jackson,hibernate-orm-panache,jdbc-postgresql')", required = false) String extensions,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (default: maven)", required = false) String buildTool,
            @ToolArg(description = "Quarkus platform version to use (e.g. '3.21.2', '999-SNAPSHOT'). "
                    + "If omitted, uses the latest release.", required = false) String quarkusVersion,
            @ToolArg(description = "Group ID of the target platform BOM (e.g. 'io.quarkus'). If omitted use 'io.quarkus'", required = false) String platformGroupId,
            @ToolArg(description = "Artifact ID of the target platform BOM", required = false) String platformArtifactId,
            @ToolArg(description = "Version of the target platform BOM", required = false) String platformVersion,
            @ToolArg(description = "If true, create the project directly in outputDir instead of a subdirectory. "
                    + "Set to true when the user asks to create the project 'here', 'in the current directory', "
                    + "or 'in this directory'. If omitted, auto-detects: when the outputDir name matches "
                    + "the artifactId and the directory is empty, the project is created in-place.",
                    required = false) Boolean createInCurrentDir,
            @ToolArg(description = "Whether to skip starter code from extension codestarts. "
                    + "If not specified, ask the user before creating the project. "
                    + "Some extensions provide useful starter code tailored to the extension; "
                    + "others only generate a basic hello world.") boolean noCode,
            @ToolArg(description = "Whether to skip generating the Maven/Gradle wrapper scripts. "
                    + "If not specified, ask the user before creating the project.") boolean noWrapper,
            @ToolArg(description = "HTTP port for the Quarkus application when it auto-starts in dev mode (e.g. 8081). "
                    + "If omitted, defaults to 8080. When 8080 is already in use, "
                    + "an available port is assigned automatically.", required = false) Integer httpPort,
            @ToolArg(description = "Comma-separated Maven profile(s) to activate when the app auto-starts in dev mode "
                    + "(e.g. 'myprofile' or 'p1,p2'). Ignored for Gradle builds.", required = false) String mavenProfiles) {
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
                if (!outDir.mkdirs()) {
                    return ToolResponse.error("Failed to create output directory: " + outputDir);
                }
            }

            boolean createInPlace = shouldCreateInPlace(createInCurrentDir, outDir, resolvedArtifactId);
            if (createInPlace && !isDirectoryEmptyEnough(outDir)) {
                if (createInCurrentDir != null && createInCurrentDir) {
                    return ToolResponse.error(
                            "Cannot create project in current directory: it contains non-hidden files. "
                                    + "Use an empty directory or remove existing files first.");
                }
                createInPlace = false;
            }

            List<String> command = buildCommand(outDir, resolvedGroupId, resolvedArtifactId, extensions, buildTool,
                    resolvedVersion, platformGroupId, platformArtifactId, platformVersion, noCode, noWrapper);
            LOG.infof("Creating Quarkus app: %s", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(outDir)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            int exitCode;
            StringBuilder outputCapture = new StringBuilder();
            Thread captureThread = new Thread(() -> {
                try {
                    outputCapture.append(ProcessUtils.captureOutput(process));
                } catch (IOException e) {
                    LOG.debugf("Error capturing process output: %s", e.getMessage());
                }
            }, "create-output-capture");
            captureThread.setDaemon(true);
            captureThread.start();
            try {
                if (!process.waitFor(10, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    captureThread.join(5000);
                    return ToolResponse.error("Project creation timed out after 10 minutes.");
                }
                captureThread.join(5000);
                exitCode = process.exitValue();
                output = outputCapture.toString();
            } finally {
                process.destroyForcibly();
            }

            if (exitCode != 0) {
                return ToolResponse.error("Project creation failed (exit " + exitCode + "):\n" + output);
            }

            String projectDir;
            if (createInPlace) {
                File subDir = new File(outDir, resolvedArtifactId);
                moveContentsUp(subDir, outDir);
                projectDir = outDir.getAbsolutePath();
                LOG.infof("Created project in-place at: %s", projectDir);
            } else {
                projectDir = new File(outDir, resolvedArtifactId).getAbsolutePath();
            }

            // Ensure rest-assured is available for testing (codestarts may not include it)
            addRestAssuredIfMissing(projectDir);

            // Ensure source directories exist so dev mode watches them from the start.
            // If src/test/java doesn't exist when dev mode starts, the file watcher
            // never registers it and tests added later won't be discovered without a
            // full restart.
            createSourceDirectories(projectDir);

            // Generate AGENTS.md, CLAUDE.md, and .mcp.json
            ProjectFiles.generateProjectInstructions(projectDir, processManager.isDevMode());
            ProjectFiles.generateMcpConfig(projectDir);

            // Auto-start the app in dev mode and wait for it to be ready
            try {
                Integer effectivePort = processManager.start(projectDir, buildTool, httpPort, mavenProfiles);
                LOG.infof("Auto-started Quarkus app at: %s", projectDir);

                // Block until the app is ready so the agent doesn't need to poll
                QuarkusInstance instance = processManager.getInstance(projectDir);
                if (instance != null && instance.getStatus() == QuarkusInstance.Status.STARTING) {
                    long deadline = System.currentTimeMillis() + LifecycleTools.STARTUP_TIMEOUT_MS;
                    while (instance.getStatus() == QuarkusInstance.Status.STARTING
                            && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(LifecycleTools.STARTUP_POLL_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                StringBuilder response = new StringBuilder();
                if (instance != null && instance.getStatus() == QuarkusInstance.Status.RUNNING) {
                    response.append("Quarkus project created and running at: ").append(projectDir)
                            .append(" (port: ").append(instance.getHttpPort()).append(")");
                } else {
                    String portInfo = effectivePort != null ? " (port: " + effectivePort + ")" : "";
                    response.append("Quarkus project created and starting at: ").append(projectDir).append(portInfo);
                }
                response.append(ContainerRuntimeChecker.containerWarning(projectDir));

                // Include skill index so the agent doesn't need a separate quarkus_skills call
                try {
                    List<SkillReader.SkillInfo> skillList = skillReader.readSkills(projectDir, null, true, false);
                    if (!skillList.isEmpty()) {
                        response.append("\n\n").append(DevMcpProxyTools.formatSkillIndex(skillList));
                    }
                } catch (Exception skillErr) {
                    LOG.debugf("Could not load skills during create: %s", skillErr.getMessage());
                }

                response.append("\n\nNEXT: Call quarkus_skills with the relevant extension names "
                        + "(e.g., quarkus_skills query='panache,rest') to read the full patterns and guidelines before writing code. "
                        + "Write code and tests, then run tests");
                if (processManager.isDevMode()) {
                    response.append(" with quarkus_callTool toolName='devui-testing_runTests'.");
                } else {
                    response.append(" with mvn test (or gradle test).");
                }

                return ToolResponse.success(response.toString());
            } catch (Exception startError) {
                LOG.warnf("Project created but failed to auto-start: %s", startError.getMessage());
                return ToolResponse.success("Quarkus project created at: " + projectDir
                        + "\nAuto-start failed: " + startError.getMessage()
                        + "\nUse quarkus_start with projectDir='" + projectDir + "' to start it manually.");
            }
        } catch (Exception e) {
            LOG.error("Failed to create Quarkus project", e);
            return ToolResponse.error("Failed to create project: " + e.getMessage());
        }
    }

    private List<String> buildCommand(File outputDir, String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion, String platformGroupId,
            String platformArtifactId, String platformVersion, boolean noCode, boolean noWrapper) {
        String cmd = resolveCreateCommand();
        return switch (cmd) {
            case "quarkus" -> buildQuarkusCliCommand("quarkus", groupId, artifactId, extensions, buildTool,
                    quarkusVersion, noCode, noWrapper);
            case "mvn" -> buildMavenCommand(groupId, artifactId, extensions, buildTool, quarkusVersion,
                    platformGroupId, platformArtifactId, platformVersion, noCode, noWrapper);
            case "jbang" -> buildJBangCommand(groupId, artifactId, extensions, buildTool, quarkusVersion, noCode,
                    noWrapper);
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
            String extensions, String buildTool, String quarkusVersion, boolean noCode, boolean noWrapper) {
        return buildCliStyleCommand(List.of(quarkusCmd), groupId, artifactId, extensions, buildTool, quarkusVersion,
                noCode, noWrapper);
    }

    private List<String> buildJBangCommand(String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion, boolean noCode, boolean noWrapper) {
        return buildCliStyleCommand(List.of("jbang", "quarkus@quarkusio"), groupId, artifactId, extensions, buildTool,
                quarkusVersion, noCode, noWrapper);
    }

    private List<String> buildCliStyleCommand(List<String> prefix, String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion, boolean noCode, boolean noWrapper) {
        List<String> cmd = new ArrayList<>(prefix);
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        if (noCode) {
            cmd.add("--no-code");
        }
        if (noWrapper) {
            cmd.add("--no-wrapper");
        }
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
            String extensions, String buildTool, String quarkusVersion, String platformGroupId,
            String platformArtifactId, String platformVersion, boolean noCode, boolean noWrapper) {
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
        if (noCode) {
            cmd.add("-DnoCode=true");
        }
        if (noWrapper) {
            cmd.add("-DnoBuildToolWrapper=true");
        }
        cmd.add("-B");

        if (platformGroupId != null && !platformGroupId.isBlank()) {
            cmd.add("-DplatformGroupId=" + platformGroupId);
        }
        if (platformArtifactId != null && !platformArtifactId.isBlank()) {
            cmd.add("-DplatformArtifactId=" + platformArtifactId);
        }
        if (platformVersion != null && !platformVersion.isBlank()) {
            cmd.add("-DplatformVersion=" + platformVersion);
        } else if (quarkusVersion != null && !quarkusVersion.isBlank()) {
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
        return ProcessUtils.isCommandAvailable(command);
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
            LOG.warnf("Failed to create source directories in %s: %s", projectDir, e.getMessage());
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
            LOG.warnf("Failed to add rest-assured dependency to %s: %s", pomPath, e.getMessage());
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
            LOG.warnf("Failed to add rest-assured dependency to %s: %s", buildFile, e.getMessage());
        }
    }

    private boolean shouldCreateInPlace(Boolean createInCurrentDir, File outDir, String artifactId) {
        if (createInCurrentDir != null) {
            return createInCurrentDir;
        }
        return outDir.getName().equals(artifactId);
    }

    private boolean isDirectoryEmptyEnough(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return true;
        }
        for (File f : files) {
            if (!f.getName().startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    private void moveContentsUp(File subDir, File targetDir) throws IOException {
        File[] files = subDir.listFiles();
        if (files != null) {
            for (File file : files) {
                Files.move(file.toPath(), targetDir.toPath().resolve(file.getName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.delete(subDir.toPath());
    }

}
