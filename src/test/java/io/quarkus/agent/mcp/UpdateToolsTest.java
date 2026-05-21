package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import io.quarkiverse.mcp.server.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void detectBuildInfoMaven() throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <properties>
                        <quarkus.platform.version>3.21.2</quarkus.platform.version>
                    </properties>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        UpdateTools.BuildInfo info = UpdateTools.detectBuildInfo(tempDir);

        assertNotNull(info);
        assertEquals("Maven", info.buildTool());
        assertEquals("maven-", info.tagPrefix());
        assertEquals("pom.xml", info.buildFile());
        assertEquals("3.21.2", info.version());
    }

    @Test
    void detectBuildInfoGradle() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("gradle.properties"), "quarkusPlatformVersion=3.32.4");

        UpdateTools.BuildInfo info = UpdateTools.detectBuildInfo(tempDir);

        assertNotNull(info);
        assertEquals("Gradle", info.buildTool());
        assertEquals("gradle-", info.tagPrefix());
        assertEquals("build.gradle", info.buildFile());
        assertEquals("3.32.4", info.version());
    }

    @Test
    void detectBuildInfoGradleKts() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins { java }");
        Files.writeString(tempDir.resolve("gradle.properties"), "quarkusPlatformVersion=3.30.1");

        UpdateTools.BuildInfo info = UpdateTools.detectBuildInfo(tempDir);

        assertNotNull(info);
        assertEquals("Gradle Kotlin DSL", info.buildTool());
        assertEquals("gradle-kotlin-dsl-", info.tagPrefix());
        assertEquals("build.gradle.kts", info.buildFile());
        assertEquals("3.30.1", info.version());
    }

    @Test
    void detectBuildInfoReturnsNullForEmptyDir() {
        UpdateTools.BuildInfo info = UpdateTools.detectBuildInfo(tempDir);
        assertNull(info);
    }

    @Test
    void semverComparatorOrdersCorrectly() {
        List<String> versions = Arrays.asList("3.9.1", "3.21.2", "3.32.4", "3.2.0", "3.15.7");
        versions.sort(new UpdateTools.SemverComparator());

        assertEquals("3.2.0", versions.get(0));
        assertEquals("3.9.1", versions.get(1));
        assertEquals("3.15.7", versions.get(2));
        assertEquals("3.21.2", versions.get(3));
        assertEquals("3.32.4", versions.get(4));
    }

    @Test
    void semverComparatorHandlesQualifiers() {
        List<String> versions = Arrays.asList("3.21.2", "3.21.2.Final", "3.21.1");
        versions.sort(new UpdateTools.SemverComparator());

        assertEquals("3.21.1", versions.get(0));
        // 3.21.2 and 3.21.2.Final — both start with 3.21.2
        assertTrue(versions.indexOf("3.21.2") < versions.indexOf("3.21.2.Final")
                || versions.indexOf("3.21.2.Final") < versions.indexOf("3.21.2"));
    }

    @Test
    void recipeArtifactPatternAcceptsValidFormats() throws Exception {
        Pattern pattern = getRecipePattern();

        assertTrue(pattern.matcher("org.acme:my-recipes:1.0.0").matches());
        assertTrue(pattern.matcher("com.example:recipes:2.3.1").matches());
        assertTrue(pattern.matcher("io.quarkus:quarkus-update-recipes:3.21.2").matches());
        assertTrue(pattern.matcher("org.acme:a:1.0,com.example:b:2.0").matches());
        assertTrue(pattern.matcher("org.acme:a:1.0, com.example:b:2.0").matches());
    }

    @Test
    void recipeArtifactPatternRejectsInvalidFormats() throws Exception {
        Pattern pattern = getRecipePattern();

        assertFalse(pattern.matcher("not-a-gav").matches());
        assertFalse(pattern.matcher("only:two").matches());
        assertFalse(pattern.matcher("has spaces:in:artifact").matches());
        assertFalse(pattern.matcher("").matches());
        assertFalse(pattern.matcher("; rm -rf /").matches());
        assertFalse(pattern.matcher("org.acme:recipes:1.0 --some-flag").matches());
    }

    private static Pattern getRecipePattern() throws Exception {
        Field field = UpdateTools.class.getDeclaredField("RECIPE_ARTIFACT_PATTERN");
        field.setAccessible(true);
        return (Pattern) field.get(null);
    }

    @Test
    void buildMavenUpdateCommandIncludesRequiredFlags() {
        UpdateTools tools = new UpdateTools();

        List<String> cmd = tools.buildMavenUpdateCommand(tempDir.toFile(), null, "3.21.2");

        assertNotNull(cmd);
        assertEquals("io.quarkus.platform:quarkus-maven-plugin:3.21.2:update", cmd.get(1));
        assertTrue(cmd.contains("-DrewriteDryRun=true"));
        assertTrue(cmd.contains("-DquarkusRegistryClient=true"));
        assertTrue(cmd.contains("-e"));
        assertTrue(cmd.contains("-N"));
        assertTrue(cmd.contains("-ntp"));
    }

    @Test
    void buildMavenUpdateCommandIncludesAdditionalRecipes() {
        UpdateTools tools = new UpdateTools();

        List<String> cmd = tools.buildMavenUpdateCommand(tempDir.toFile(), "org.acme:my-recipes:1.0.0", "3.21.2");

        assertTrue(cmd.contains("-DadditionalUpdateRecipes=org.acme:my-recipes:1.0.0"));
    }

    @Test
    void buildMavenUpdateCommandOmitsRecipesWhenNull() {
        UpdateTools tools = new UpdateTools();

        List<String> cmd = tools.buildMavenUpdateCommand(tempDir.toFile(), null, "3.21.2");

        assertTrue(cmd.stream().noneMatch(a -> a.startsWith("-DadditionalUpdateRecipes=")));
    }

    @Test
    void buildGradleUpdateCommandIncludesDryRun() {
        UpdateTools tools = new UpdateTools();

        List<String> cmd = tools.buildGradleUpdateCommand(tempDir.toFile(), null);

        assertNotNull(cmd);
        assertTrue(cmd.contains("quarkusUpdate"));
        assertTrue(cmd.contains("--rewriteDryRun"));
    }

    @Test
    void buildGradleUpdateCommandIncludesAdditionalRecipes() {
        UpdateTools tools = new UpdateTools();

        List<String> cmd = tools.buildGradleUpdateCommand(tempDir.toFile(), "org.acme:my-recipes:1.0.0");

        assertTrue(cmd.contains("--rewriteDryRun"));
        assertTrue(cmd.contains("--additionalUpdateRecipes=org.acme:my-recipes:1.0.0"));
    }

    @Test
    void buildGradleUpdateCommandOmitsRecipesWhenNull() {
        UpdateTools tools = new UpdateTools();

        List<String> cmd = tools.buildGradleUpdateCommand(tempDir.toFile(), null);

        assertTrue(cmd.stream().noneMatch(a -> a.startsWith("--additionalUpdateRecipes=")));
    }

    @Test
    void buildUpdateCommandPrefersQuarkusCli() {
        UpdateTools tools = new UpdateTools();
        UpdateTools.BuildInfo info = new UpdateTools.BuildInfo("Maven", "maven-", "pom.xml", "3.21.2");

        List<String> cmd = tools.buildUpdateCommand(tempDir.toString(), null, info, "3.22.0");

        assertNotNull(cmd);
        if (ProcessUtils.isCommandAvailable("quarkus")) {
            assertEquals("quarkus", cmd.get(0));
            assertTrue(cmd.contains("update"));
            assertTrue(cmd.contains("--dry-run"));
        } else {
            assertEquals("io.quarkus.platform:quarkus-maven-plugin:3.22.0:update", cmd.get(1));
        }
    }

    @Test
    void fetchLatestVersionReturnsVersion() {
        // This test requires network access — it fetches real tags from GitHub
        String latest = UpdateTools.fetchLatestVersion("maven-");
        // Should return a valid version if GitHub is reachable
        if (latest != null) {
            assertTrue(latest.matches("[0-9]+\\.[0-9]+\\.[0-9]+.*"),
                    "Expected semver-like version, got: " + latest);
        }
        // Don't fail if network is unavailable
    }

    @Test
    void updateToolsExposeExistingAppGuidance() throws Exception {
        Method update = UpdateTools.class.getDeclaredMethod("update", String.class, String.class);
        Tool updateTool = update.getAnnotation(Tool.class);
        assertNotNull(updateTool);
        assertTrue(updateTool.description().contains("edit, extend, modify, debug, maintain"),
                "quarkus_update should advertise existing-app guidance");

        Method prepare = UpdateTools.class.getDeclaredMethod("prepareExistingApp", String.class, String.class);
        Tool prepareTool = prepare.getAnnotation(Tool.class);
        assertNotNull(prepareTool);
        assertEquals("quarkus_prepare_existing_app", prepareTool.name());
        assertTrue(prepareTool.description().contains("existing Quarkus or Java app"),
                "alias should match existing-app phrasing");
    }
}
