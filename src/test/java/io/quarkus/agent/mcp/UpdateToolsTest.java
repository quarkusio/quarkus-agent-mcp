package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateToolsTest {

    // --- Stream validation ---

    @ParameterizedTest
    @ValueSource(strings = {
            "3.36",
            "3.21",
            "4.0",
            "3.36.1",
    })
    void validStreams(String stream) {
        assertTrue(UpdateTools.VALID_STREAM.matcher(stream).matches(), stream + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "latest",
            "3",
            "abc",
            "3.36; echo pwned",
            "3.36 && rm -rf /",
            "${version}",
            "",
    })
    void invalidStreams(String stream) {
        assertFalse(UpdateTools.VALID_STREAM.matcher(stream).matches(), stream + " should be invalid");
    }

    // --- Additional recipes validation ---

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example:my-recipes:1.0.0",
            "com.x.y.quarkus:z-quarkus-update-recipes:1.0.0-SNAPSHOT",
            "org.acme:recipes:2.0,org.other:more:1.0",
    })
    void validRecipes(String recipes) {
        assertTrue(UpdateTools.VALID_RECIPES.matcher(recipes).matches(), recipes + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example:recipes:1.0; echo pwned",
            "recipes && rm -rf /",
            "$(whoami):recipes:1.0",
            "recipes `id`",
    })
    void invalidRecipes(String recipes) {
        assertFalse(UpdateTools.VALID_RECIPES.matcher(recipes).matches(), recipes + " should be invalid");
    }

    // --- CLI command building ---

    @Test
    void cliCommand_noOptions() {
        List<String> cmd = UpdateTools.buildCliCommand(null, null, null);
        assertEquals(List.of("quarkus", "update"), cmd);
    }

    @Test
    void cliCommand_allOptions() {
        List<String> cmd = UpdateTools.buildCliCommand("3.36", true, "com.example:recipes:1.0");
        assertEquals(List.of(
                "quarkus", "update",
                "--stream=3.36",
                "--dry-run",
                "--additional-update-recipes=com.example:recipes:1.0"), cmd);
    }

    @Test
    void cliCommand_streamOnly() {
        List<String> cmd = UpdateTools.buildCliCommand("3.36", null, null);
        assertEquals(List.of("quarkus", "update", "--stream=3.36"), cmd);
    }

    @Test
    void cliCommand_dryRunOnly() {
        List<String> cmd = UpdateTools.buildCliCommand(null, true, null);
        assertEquals(List.of("quarkus", "update", "--dry-run"), cmd);
    }

    @Test
    void cliCommand_dryRunFalse() {
        List<String> cmd = UpdateTools.buildCliCommand(null, false, null);
        assertEquals(List.of("quarkus", "update"), cmd);
    }

    @Test
    void cliCommand_blankStreamIgnored() {
        List<String> cmd = UpdateTools.buildCliCommand("  ", null, null);
        assertEquals(List.of("quarkus", "update"), cmd);
    }

    // --- Maven command building ---

    @Test
    void mavenCommand_noOptions() {
        List<String> cmd = UpdateTools.buildMavenCommand("mvn", "3.37.0", null, null, null);
        assertEquals("mvn", cmd.get(0));
        assertEquals("-DquarkusRegistryClient=true", cmd.get(1));
        assertEquals("io.quarkus:quarkus-maven-plugin:3.37.0:update", cmd.get(2));
        assertTrue(cmd.contains("-e"));
        assertTrue(cmd.contains("-N"));
        assertTrue(cmd.contains("-ntp"));
        assertFalse(cmd.stream().anyMatch(s -> s.startsWith("-Dstream=")));
        assertFalse(cmd.contains("-DrewriteDryRun"));
    }

    @Test
    void mavenCommand_allOptions() {
        List<String> cmd = UpdateTools.buildMavenCommand("./mvnw", "3.37.0", "3.36", true,
                "com.example:recipes:1.0");
        assertEquals("./mvnw", cmd.get(0));
        assertTrue(cmd.contains("-Dstream=3.36"));
        assertTrue(cmd.contains("-DrewriteDryRun"));
        assertTrue(cmd.contains("-DadditionalUpdateRecipes=com.example:recipes:1.0"));
        assertTrue(cmd.contains("-DquarkusRegistryClient=true"));
        assertTrue(cmd.contains("-e"));
        assertTrue(cmd.contains("-N"));
        assertTrue(cmd.contains("-ntp"));
    }

    @Test
    void mavenCommand_dryRunFalseNotAdded() {
        List<String> cmd = UpdateTools.buildMavenCommand("mvn", "3.37.0", null, false, null);
        assertFalse(cmd.contains("-DrewriteDryRun"));
    }

    // --- Build tool detection ---

    @Test
    void detectBuildTool_maven(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        assertEquals("maven", UpdateTools.detectBuildTool(tempDir.toFile()));
    }

    @Test
    void detectBuildTool_gradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");
        assertEquals("gradle", UpdateTools.detectBuildTool(tempDir.toFile()));
    }

    @Test
    void detectBuildTool_gradleKts(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins {}");
        assertEquals("gradle", UpdateTools.detectBuildTool(tempDir.toFile()));
    }

    @Test
    void detectBuildTool_mavenPreferredOverGradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");
        assertEquals("maven", UpdateTools.detectBuildTool(tempDir.toFile()));
    }

    @Test
    void detectBuildTool_noBuildFile(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class,
                () -> UpdateTools.detectBuildTool(tempDir.toFile()));
    }
}
