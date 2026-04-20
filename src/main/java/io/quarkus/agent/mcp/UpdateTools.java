package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * MCP tool for checking and updating Quarkus projects to the latest version.
 * Compares project build files against reference projects from
 * <a href="https://github.com/quarkusio/code-with-quarkus-compare">code-with-quarkus-compare</a>,
 * runs {@code quarkus update --dry-run} if the CLI is available, and produces
 * a unified upgrade report.
 */
public class UpdateTools {

    private static final Logger LOG = Logger.getLogger(UpdateTools.class);

    private static final String COMPARE_REPO = "quarkusio/code-with-quarkus-compare";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/" + COMPARE_REPO;
    private static final String COMPARE_URL_BASE = "https://github.com/" + COMPARE_REPO + "/compare/";

    // Semver-like: 3.21.2, 3.21.2.Final, 3.21.2-SNAPSHOT
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+([.\\-][A-Za-z0-9]+)*$");


    @Tool(name = "quarkus_update", description = "Check if a Quarkus project is up-to-date and provide an upgrade report. "
            + "Detects the current version, checks for newer releases, compares build files against "
            + "reference projects, and optionally runs 'quarkus update --dry-run' to preview migrations. "
            + "IMPORTANT: For existing Quarkus projects, ALWAYS call this tool using a subagent BEFORE "
            + "starting any development work to ensure the project is on the latest version.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_update", readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResponse update(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            Path dir = Path.of(projectDir);
            if (!Files.isDirectory(dir)) {
                return ToolResponse.error("Project directory does not exist: " + projectDir);
            }

            // Step 1: Detect build tool and version
            BuildInfo buildInfo = detectBuildInfo(dir);
            if (buildInfo == null) {
                return ToolResponse.error("Could not detect build tool or Quarkus version in: " + projectDir);
            }

            // Step 2: Find latest available version
            String latestVersion = fetchLatestVersion(buildInfo.tagPrefix);
            if (latestVersion == null) {
                return ToolResponse.error("Could not determine the latest Quarkus version from " + COMPARE_REPO);
            }

            StringBuilder report = new StringBuilder();
            report.append("# Quarkus Project Update Report\n\n");
            report.append("- **Build tool:** ").append(buildInfo.buildTool).append("\n");
            report.append("- **Current version:** ").append(buildInfo.version).append("\n");
            report.append("- **Latest version:** ").append(latestVersion).append("\n\n");

            boolean isUpToDate = buildInfo.version.equals(latestVersion);

            if (isUpToDate) {
                // Step 2b: Compare build files even if up-to-date
                report.append("Your Quarkus version is up to date.\n\n");
                String buildFileDiff = compareBuildFiles(buildInfo, buildInfo.version);
                if (buildFileDiff != null && !buildFileDiff.isBlank()) {
                    report.append("## Build File Differences\n\n");
                    report.append("Your build file has some differences compared to the reference project:\n\n");
                    report.append(buildFileDiff);
                } else {
                    report.append("Build files match the reference project.\n");
                }
                return ToolResponse.success(report.toString());
            }

            // Step 3: Project is outdated -- full upgrade analysis
            report.append("**A newer Quarkus version is available!**\n\n");

            // Step 3a: Compare build files against current version reference
            String currentDiff = compareBuildFiles(buildInfo, buildInfo.version);
            if (currentDiff != null && !currentDiff.isBlank()) {
                report.append("## Current Build File Differences\n\n");
                report.append("Differences compared to the reference for your current version:\n\n");
                report.append(currentDiff).append("\n");
            }

            // Step 3b: Run quarkus update --dry-run
            String dryRunReport = runQuarkusUpdateDryRun(projectDir);
            if (dryRunReport != null) {
                report.append("## Automated Migration Preview (`quarkus update --dry-run`)\n\n");
                report.append(dryRunReport).append("\n");
            }

            // Step 3c: Generator diff URL
            String currentTag = buildInfo.tagPrefix + buildInfo.version;
            String latestTag = buildInfo.tagPrefix + latestVersion;
            report.append("## Structural Changes Between Versions\n\n");
            report.append("Review the full diff of build file changes between versions:\n");
            report.append(COMPARE_URL_BASE).append(currentTag).append("...").append(latestTag).append("\n\n");
            report.append("This may reveal changes that `quarkus update` does not cover, such as:\n");
            report.append("- Plugin version bumps (surefire, failsafe, compiler)\n");
            report.append("- New build properties or configurations\n");
            report.append("- Wrapper script updates\n");
            report.append("- Dockerfile changes\n\n");

            // Step 4: Recommended actions
            report.append("## Recommended Actions\n\n");
            report.append("1. Run `quarkus update` to apply automated migrations");
            if (dryRunReport == null) {
                report.append(" (install the Quarkus CLI first: https://quarkus.io/guides/cli-tooling)");
            }
            report.append("\n");
            report.append("2. Review and manually apply structural changes from the comparison link above\n");
            report.append("3. Run tests to verify everything works after the update\n");

            return ToolResponse.success(report.toString());
        } catch (Exception e) {
            LOG.error("Failed to check for updates", e);
            return ToolResponse.error("Failed to check for updates: " + e.getMessage());
        }
    }

    /**
     * Detects the build tool and Quarkus version from the project directory.
     */
    static BuildInfo detectBuildInfo(Path projectDir) {
        // Check Maven
        Path pomFile = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(pomFile)) {
            String version = QuarkusVersionDetector.detect(projectDir.toString());
            if (version != null) {
                return new BuildInfo("Maven", "maven-", "pom.xml", version);
            }
        }

        // Check Gradle Kotlin DSL
        Path buildGradleKts = projectDir.resolve("build.gradle.kts");
        if (Files.isRegularFile(buildGradleKts)) {
            String version = QuarkusVersionDetector.detect(projectDir.toString());
            if (version != null) {
                return new BuildInfo("Gradle Kotlin DSL", "gradle-kotlin-dsl-", "build.gradle.kts", version);
            }
        }

        // Check Gradle
        Path buildGradle = projectDir.resolve("build.gradle");
        if (Files.isRegularFile(buildGradle)) {
            String version = QuarkusVersionDetector.detect(projectDir.toString());
            if (version != null) {
                return new BuildInfo("Gradle", "gradle-", "build.gradle", version);
            }
        }

        return null;
    }

    /**
     * Fetches the latest version for the given tag prefix from the compare repo.
     */
    static String fetchLatestVersion(String tagPrefix) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "ls-remote", "--tags",
                    "https://github.com/" + COMPARE_REPO + ".git",
                    tagPrefix + "*");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOG.debugf("git ls-remote failed with exit code %d", exitCode);
                return null;
            }

            // Parse versions from tags like: refs/tags/maven-3.32.4
            List<String> versions = new ArrayList<>();
            Pattern tagPattern = Pattern.compile("refs/tags/" + Pattern.quote(tagPrefix) + "(.+)$",
                    Pattern.MULTILINE);
            Matcher m = tagPattern.matcher(output);
            while (m.find()) {
                String version = m.group(1).trim();
                // Skip versions with ^ (annotated tag dereferences)
                if (!version.contains("^") && VERSION_PATTERN.matcher(version).matches()) {
                    versions.add(version);
                }
            }

            if (versions.isEmpty()) {
                return null;
            }

            // Sort by semver and return the latest
            versions.sort(new SemverComparator());
            return versions.get(versions.size() - 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugf("Interrupted while fetching latest version");
            return null;
        } catch (IOException e) {
            LOG.debugf("Failed to fetch latest version: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Compares the project's build file against the reference from code-with-quarkus-compare.
     */
    private String compareBuildFiles(BuildInfo buildInfo, String version) {
        String tag = buildInfo.tagPrefix + version;
        String url = RAW_BASE + "/" + tag + "/" + buildInfo.buildFile;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClientProvider.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.debugf("Reference build file not found at %s (HTTP %d)", url, response.statusCode());
                return "No reference build file available for version " + version
                        + ". Check [available tags](https://github.com/" + COMPARE_REPO + "/tags).\n";
            }

            return "Reference build file fetched from: " + url + "\n"
                    + "Compare your `" + buildInfo.buildFile + "` against this reference, focusing on:\n"
                    + "- Plugin versions and configurations (compiler, surefire, failsafe, quarkus-maven-plugin)\n"
                    + "- BOM setup and dependency management structure\n"
                    + "- Build properties (Java version, encoding, surefire-plugin.version)\n"
                    + "- Wrapper scripts (`.mvnw`/`gradlew` presence and version)\n\n"
                    + "Ignore user-specific content (custom dependencies, groupId, artifactId, profiles).\n\n"
                    + "```\n" + response.body() + "\n```\n";
        } catch (Exception e) {
            LOG.debugf("Failed to fetch reference build file: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Runs {@code quarkus update --dry-run} and returns the report, or null if CLI not available.
     */
    private String runQuarkusUpdateDryRun(String projectDir) {
        // Check if quarkus CLI is available
        if (!isCommandAvailable("quarkus")) {
            return null;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("quarkus", "update", "--dry-run")
                    .directory(new File(projectDir))
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
            }

            int exitCode = process.waitFor();

            StringBuilder report = new StringBuilder();
            report.append("Console output:\n```\n").append(output.trim()).append("\n```\n\n");

            // Read the generated patch if available
            Path patchFile = Path.of(projectDir, "target", "rewrite", "rewrite.patch");
            if (Files.isRegularFile(patchFile)) {
                String patch = Files.readString(patchFile, StandardCharsets.UTF_8);
                if (!patch.isBlank()) {
                    report.append("Generated patch (`target/rewrite/rewrite.patch`):\n```diff\n");
                    report.append(patch.trim()).append("\n```\n");
                }
            }

            if (exitCode != 0) {
                report.insert(0, "**Note:** `quarkus update --dry-run` exited with code " + exitCode + "\n\n");
            }

            return report.toString();
        } catch (Exception e) {
            LOG.debugf("Failed to run quarkus update --dry-run: %s", e.getMessage());
            return "Failed to run `quarkus update --dry-run`: " + e.getMessage() + "\n";
        }
    }

    private boolean isCommandAvailable(String command) {
        return ProcessUtils.isCommandAvailable(command);
    }

    record BuildInfo(String buildTool, String tagPrefix, String buildFile, String version) {
    }

    /**
     * Compares version strings using semver-like ordering.
     * Handles versions like 3.21.2, 3.32.4, 3.9.1 correctly.
     */
    static class SemverComparator implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            String[] partsA = a.split("[.\\-]");
            String[] partsB = b.split("[.\\-]");
            int len = Math.max(partsA.length, partsB.length);
            for (int i = 0; i < len; i++) {
                String pa = i < partsA.length ? partsA[i] : "0";
                String pb = i < partsB.length ? partsB[i] : "0";
                try {
                    int cmp = Integer.compare(Integer.parseInt(pa), Integer.parseInt(pb));
                    if (cmp != 0)
                        return cmp;
                } catch (NumberFormatException e) {
                    int cmp = pa.compareTo(pb);
                    if (cmp != 0)
                        return cmp;
                }
            }
            return 0;
        }
    }
}
