package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

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

    // Maven coordinate segments: letters, digits, dots, hyphens, underscores
    private static final Pattern VALID_MAVEN_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");

    // Extensions: comma-separated list of extension short names
    private static final Pattern VALID_EXTENSIONS = Pattern.compile("^[a-zA-Z0-9._,:-]+$");

    // Cache which command is available — doesn't change during the lifetime of the server
    private volatile String cachedCreateCommand;

    @Tool(name = "quarkus/create", description = "Create a new Quarkus application project and automatically start it in dev mode. "
            + "Creates a Maven or Gradle project with the specified extensions, then starts it. "
            + "Once running, Quarkus dev mode provides hot reload — changes are recompiled when the "
            + "app is next accessed (e.g., via an HTTP request or when tests run). "
            + "DEVELOPMENT WORKFLOW: After the app is running, use quarkus/searchTools with query 'test' "
            + "to find the continuous testing tools, then follow this cycle: "
            + "1) Pause continuous testing before making code changes, "
            + "2) Make and save all your code changes, "
            + "3) Resume continuous testing — this triggers hot reload and runs tests against your updated code, "
            + "4) Check quarkus/logs for test results and fix any failures. "
            + "TIP: Use quarkus/searchDocs to look up Quarkus APIs and best practices before writing code.")
    ToolResponse create(
            @ToolArg(description = "Absolute path to the directory where the project will be created. "
                    + "A subdirectory named after the artifactId will be created inside this directory.") String outputDir,
            @ToolArg(description = "The Maven groupId for the project (e.g. 'com.example')", required = false) String groupId,
            @ToolArg(description = "The Maven artifactId for the project (e.g. 'my-app').", required = false) String artifactId,
            @ToolArg(description = "Comma-separated list of Quarkus extensions to include "
                    + "(e.g. 'rest-jackson,hibernate-orm-panache,jdbc-postgresql')", required = false) String extensions,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (default: maven)", required = false) String buildTool) {
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

            File outDir = new File(outputDir);
            if (!outDir.isDirectory()) {
                return ToolResponse.error("Output directory does not exist: " + outputDir);
            }

            List<String> command = buildCommand(outDir, resolvedGroupId, resolvedArtifactId, extensions, buildTool);
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

            // Auto-start the app in dev mode
            try {
                processManager.start(projectDir, buildTool);
                LOG.infof("Auto-started Quarkus app at: %s", projectDir);
                return ToolResponse.success("Quarkus project created and starting in dev mode at: " + projectDir
                        + "\nHot reload is active — file changes are automatically detected and recompiled."
                        + "\nUse quarkus/status to check when it's ready."
                        + "\nUse quarkus/searchTools with query 'test' to find continuous testing tools.");
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
            String extensions, String buildTool) {
        String cmd = resolveCreateCommand();
        return switch (cmd) {
            case "quarkus" -> buildQuarkusCliCommand("quarkus", groupId, artifactId, extensions, buildTool);
            case "mvn" -> buildMavenCommand(groupId, artifactId, extensions, buildTool);
            case "jbang" -> buildJBangCommand(groupId, artifactId, extensions, buildTool);
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
            String extensions, String buildTool) {
        List<String> cmd = new ArrayList<>();
        cmd.add(quarkusCmd);
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        cmd.add("--no-code");
        cmd.add("--batch-mode");

        if (extensions != null && !extensions.isBlank()) {
            cmd.add("--extension=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("--gradle");
        }

        return cmd;
    }

    private List<String> buildJBangCommand(String groupId, String artifactId,
            String extensions, String buildTool) {
        List<String> cmd = new ArrayList<>();
        cmd.add("jbang");
        cmd.add("quarkus@quarkusio");
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        cmd.add("--no-code");
        cmd.add("--batch-mode");

        if (extensions != null && !extensions.isBlank()) {
            cmd.add("--extension=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("--gradle");
        }

        return cmd;
    }

    private List<String> buildMavenCommand(String groupId, String artifactId,
            String extensions, String buildTool) {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        cmd.add("io.quarkus.platform:quarkus-maven-plugin:create");
        cmd.add("-DprojectGroupId=" + groupId);
        cmd.add("-DprojectArtifactId=" + artifactId);
        cmd.add("-DnoCode=true");
        cmd.add("-B");

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
}
