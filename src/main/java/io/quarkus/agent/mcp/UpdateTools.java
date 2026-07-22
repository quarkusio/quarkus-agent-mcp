package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

public class UpdateTools {

    private static final Logger LOG = Logger.getLogger(UpdateTools.class);

    @Inject
    LatestQuarkusVersionResolver latestVersionResolver;

    static final Pattern VALID_STREAM = Pattern.compile("^[0-9]+\\.[0-9]+[a-zA-Z0-9._-]*$");
    static final Pattern VALID_RECIPES = Pattern.compile("^[a-zA-Z0-9._:,/-]+$");

    @Tool(name = "quarkus_update", description = "Run the Quarkus update tool against an existing project. "
            + "Updates the project to a newer Quarkus version by applying OpenRewrite recipes. "
            + "Use with dryRun=true to preview changes before applying. "
            + "After a non-dry-run update, do a full quarkus_stop + quarkus_start cycle to pick up the new dependencies.")
    ToolResponse update(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Target Quarkus stream (e.g. '3.36'). "
                    + "If omitted, updates to the latest available version.", required = false) String stream,
            @ToolArg(description = "If true, only show what would change without applying modifications.",
                    required = false) Boolean dryRun,
            @ToolArg(description = "Maven coordinates of additional update recipes to apply "
                    + "(e.g. 'com.example:my-recipes:1.0.0'). "
                    + "Multiple recipes can be comma-separated.", required = false) String additionalUpdateRecipes) {
        try {
            File dir = new File(projectDir);
            if (!dir.isDirectory()) {
                return ToolResponse.error("Not a directory: " + projectDir);
            }

            if (stream != null && !stream.isBlank() && !VALID_STREAM.matcher(stream).matches()) {
                return ToolResponse.error(
                        "Invalid stream: must be a version stream like '3.36'. Only digits, dots, letters, hyphens, underscores allowed.");
            }
            if (additionalUpdateRecipes != null && !additionalUpdateRecipes.isBlank()
                    && !VALID_RECIPES.matcher(additionalUpdateRecipes).matches()) {
                return ToolResponse.error(
                        "Invalid additionalUpdateRecipes: must contain only letters, digits, dots, hyphens, underscores, colons, commas, slashes.");
            }

            String buildTool = detectBuildTool(dir);
            List<String> command = buildCommand(dir, buildTool, stream, dryRun, additionalUpdateRecipes);
            LOG.infof("Running Quarkus update: %s", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder outputCapture = new StringBuilder();
            Thread captureThread = new Thread(() -> {
                try {
                    outputCapture.append(ProcessUtils.captureOutput(process));
                } catch (IOException e) {
                    LOG.debugf("Error capturing process output: %s", e.getMessage());
                }
            }, "update-output-capture");
            captureThread.setDaemon(true);
            captureThread.start();

            int exitCode;
            String output;
            try {
                if (!process.waitFor(10, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    captureThread.join(5000);
                    return ToolResponse.error("Quarkus update timed out after 10 minutes.");
                }
                captureThread.join(5000);
                exitCode = process.exitValue();
                output = outputCapture.toString();
            } finally {
                process.destroyForcibly();
            }

            if (exitCode != 0) {
                return ToolResponse.error("Quarkus update failed (exit " + exitCode + "):\n" + output);
            }

            if (!Boolean.TRUE.equals(dryRun)) {
                QuarkusVersionDetector.invalidate(projectDir);
            }

            return ToolResponse.success(output);
        } catch (Exception e) {
            LOG.error("Failed to run Quarkus update at " + projectDir, e);
            return ToolResponse.error("Failed to run Quarkus update: " + e.getMessage());
        }
    }

    List<String> buildCommand(File projectDir, String buildTool, String stream, Boolean dryRun,
            String additionalUpdateRecipes) {
        if (ProcessUtils.isCommandAvailable("quarkus")) {
            return buildCliCommand(stream, dryRun, additionalUpdateRecipes);
        }
        if ("maven".equals(buildTool)) {
            return buildMavenCommand(projectDir, stream, dryRun, additionalUpdateRecipes);
        }
        throw new IllegalStateException(
                "Cannot run Quarkus update: Quarkus CLI not found and project uses Gradle. "
                        + "Install the Quarkus CLI (https://quarkus.io/guides/cli-tooling) to update Gradle projects.");
    }

    static List<String> buildCliCommand(String stream, Boolean dryRun, String additionalUpdateRecipes) {
        List<String> cmd = new ArrayList<>();
        cmd.add("quarkus");
        cmd.add("update");
        if (stream != null && !stream.isBlank()) {
            cmd.add("--stream=" + stream);
        }
        if (Boolean.TRUE.equals(dryRun)) {
            cmd.add("--dry-run");
        }
        if (additionalUpdateRecipes != null && !additionalUpdateRecipes.isBlank()) {
            cmd.add("--additional-update-recipes=" + additionalUpdateRecipes);
        }
        return cmd;
    }

    List<String> buildMavenCommand(File projectDir, String stream, Boolean dryRun,
            String additionalUpdateRecipes) {
        String mvnCmd = ProcessUtils.resolveMavenCommand(projectDir);
        String pluginVersion = resolvePluginVersion(projectDir.getAbsolutePath());
        return buildMavenCommand(mvnCmd, pluginVersion, stream, dryRun, additionalUpdateRecipes);
    }

    static List<String> buildMavenCommand(String mvnCmd, String pluginVersion, String stream,
            Boolean dryRun, String additionalUpdateRecipes) {
        List<String> cmd = new ArrayList<>();
        cmd.add(mvnCmd);
        cmd.add("-DquarkusRegistryClient=true");
        cmd.add("io.quarkus:quarkus-maven-plugin:" + pluginVersion + ":update");
        cmd.add("-e");
        cmd.add("-N");
        cmd.add("-ntp");
        if (stream != null && !stream.isBlank()) {
            cmd.add("-Dstream=" + stream);
        }
        if (Boolean.TRUE.equals(dryRun)) {
            cmd.add("-DrewriteDryRun");
        }
        if (additionalUpdateRecipes != null && !additionalUpdateRecipes.isBlank()) {
            cmd.add("-DadditionalUpdateRecipes=" + additionalUpdateRecipes);
        }
        return cmd;
    }

    private String resolvePluginVersion(String projectDir) {
        String latest = latestVersionResolver.resolve(projectDir);
        if (latest != null) {
            return latest;
        }
        String detected = QuarkusVersionDetector.detect(projectDir);
        if (detected != null) {
            return detected;
        }
        throw new IllegalStateException(
                "Cannot determine Quarkus version for the Maven update plugin. "
                        + "Specify a stream or ensure the project has a detectable Quarkus version.");
    }

    static String detectBuildTool(File projectDir) {
        if (new File(projectDir, "pom.xml").exists()) {
            return "maven";
        } else if (new File(projectDir, "build.gradle").exists()
                || new File(projectDir, "build.gradle.kts").exists()) {
            return "gradle";
        }
        throw new IllegalArgumentException(
                "Cannot detect build tool at: " + projectDir + ". No pom.xml or build.gradle found.");
    }
}
