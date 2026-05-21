package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkiverse.mcp.server.Tool;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the input validation patterns used in CreateTools.
 * These are tested directly against the regex patterns rather than through
 * the full tool call (which requires CDI).
 */
class CreateToolsValidationTest {

    // Must match the patterns in CreateTools
    private static final Pattern VALID_MAVEN_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern VALID_EXTENSIONS = Pattern.compile("^[a-zA-Z0-9._,:-]+$");

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example",
            "org.acme",
            "my-app",
            "my_app",
            "my.group.id",
            "MyApp123"
    })
    void validMavenIds(String id) {
        assertTrue(VALID_MAVEN_ID.matcher(id).matches(), id + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com example",     // space
            "my;app",          // semicolon
            "my$app",          // dollar
            "my&app",          // ampersand
            "my|app",          // pipe
            "my`app`",         // backtick
            "my$(whoami)app",  // command substitution
            "my\napp",         // newline
            "",                // empty
    })
    void invalidMavenIds(String id) {
        assertFalse(VALID_MAVEN_ID.matcher(id).matches(), id + " should be invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rest-jackson",
            "rest-jackson,hibernate-orm-panache",
            "io.quarkus:quarkus-rest:3.21.2",
            "rest-jackson,jdbc-postgresql,hibernate-orm-panache",
    })
    void validExtensions(String ext) {
        assertTrue(VALID_EXTENSIONS.matcher(ext).matches(), ext + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rest jackson",       // space
            "rest;jackson",       // semicolon
            "rest&jackson",       // ampersand
            "rest$(whoami)",      // command substitution
    })
    void invalidExtensions(String ext) {
        assertFalse(VALID_EXTENSIONS.matcher(ext).matches(), ext + " should be invalid");
    }

    @Test
    void inPlaceDetection_explicitTrue() {
        File dir = new File("/some/path/my-app");
        assertTrue(shouldCreateInPlace(true, dir, "other-name"));
    }

    @Test
    void inPlaceDetection_explicitFalse() {
        File dir = new File("/some/path/my-app");
        assertFalse(shouldCreateInPlace(false, dir, "my-app"));
    }

    @Test
    void inPlaceDetection_autoMatchingName() {
        File dir = new File("/some/path/my-app");
        assertTrue(shouldCreateInPlace(null, dir, "my-app"));
    }

    @Test
    void inPlaceDetection_autoNonMatchingName() {
        File dir = new File("/some/path/projects");
        assertFalse(shouldCreateInPlace(null, dir, "my-app"));
    }

    @Test
    void directoryEmptyEnough_emptyDir(@TempDir Path tempDir) {
        assertTrue(isDirectoryEmptyEnough(tempDir.toFile()));
    }

    @Test
    void directoryEmptyEnough_onlyDotFiles(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve(".gitignore"));
        Files.createDirectory(tempDir.resolve(".git"));
        Files.createDirectory(tempDir.resolve(".claude"));
        assertTrue(isDirectoryEmptyEnough(tempDir.toFile()));
    }

    @Test
    void directoryEmptyEnough_hasNonHiddenFiles(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("README.md"));
        assertFalse(isDirectoryEmptyEnough(tempDir.toFile()));
    }

    @Test
    void directoryEmptyEnough_hasNonHiddenDir(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("src"));
        assertFalse(isDirectoryEmptyEnough(tempDir.toFile()));
    }

    @Test
    void moveContentsUp(@TempDir Path tempDir) throws IOException {
        Path subDir = Files.createDirectory(tempDir.resolve("my-app"));
        Files.writeString(subDir.resolve("pom.xml"), "<project/>");
        Files.createDirectory(subDir.resolve("src"));
        Files.writeString(subDir.resolve("src").resolve("Main.java"), "class Main {}");

        moveContentsUp(subDir.toFile(), tempDir.toFile());

        assertTrue(Files.exists(tempDir.resolve("pom.xml")));
        assertTrue(Files.exists(tempDir.resolve("src")));
        assertTrue(Files.exists(tempDir.resolve("src").resolve("Main.java")));
        assertFalse(Files.exists(subDir), "subdirectory should be removed");
    }

    @Test
    void moveContentsUp_replacesExistingDotfiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".gitignore"), "old-content");

        Path subDir = Files.createDirectory(tempDir.resolve("my-app"));
        Files.writeString(subDir.resolve(".gitignore"), "project-content");
        Files.writeString(subDir.resolve("pom.xml"), "<project/>");

        moveContentsUp(subDir.toFile(), tempDir.toFile());

        assertEquals("project-content", Files.readString(tempDir.resolve(".gitignore")));
        assertTrue(Files.exists(tempDir.resolve("pom.xml")));
        assertFalse(Files.exists(subDir), "subdirectory should be removed");
    }

    private static boolean shouldCreateInPlace(Boolean createInCurrentDir, File outDir, String artifactId) {
        if (createInCurrentDir != null) {
            return createInCurrentDir;
        }
        return outDir.getName().equals(artifactId);
    }

    private static boolean isDirectoryEmptyEnough(File dir) {
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

    private static void moveContentsUp(File subDir, File targetDir) throws IOException {
        File[] files = subDir.listFiles();
        if (files != null) {
            for (File file : files) {
                Files.move(file.toPath(), targetDir.toPath().resolve(file.getName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.delete(subDir.toPath());
    }

    @Test
    void versionValidationPattern() {
        // Same pattern as QuarkusVersionDetector.VALID_VERSION
        Pattern VALID_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+([.\\-][A-Za-z0-9]+)*$");

        assertTrue(VALID_VERSION.matcher("3.21.2").matches());
        assertTrue(VALID_VERSION.matcher("3.21.2.Final").matches());
        assertTrue(VALID_VERSION.matcher("3.21.2-SNAPSHOT").matches());
        assertTrue(VALID_VERSION.matcher("3.21.0.CR1").matches());
        assertTrue(VALID_VERSION.matcher("10.100.200").matches());

        assertFalse(VALID_VERSION.matcher("latest").matches());
        assertFalse(VALID_VERSION.matcher("3.21").matches());
        assertFalse(VALID_VERSION.matcher("${version}").matches());
        assertFalse(VALID_VERSION.matcher("latest && rm -rf /").matches());
        assertFalse(VALID_VERSION.matcher("evil.com/backdoor:latest").matches());
        assertFalse(VALID_VERSION.matcher("").matches());
        assertFalse(VALID_VERSION.matcher("3.21.2; echo pwned").matches());
    }

    @Test
    void createToolsExposeAppCreationGuidance() throws Exception {
        Method create = CreateTools.class.getDeclaredMethod("create", String.class, String.class, String.class, String.class,
                String.class, String.class, Boolean.class, boolean.class, boolean.class, Integer.class);
        Tool createTool = create.getAnnotation(Tool.class);
        assertNotNull(createTool);
        assertTrue(createTool.description().contains("create, scaffold, bootstrap"),
                "quarkus_create should advertise creation/scaffolding guidance");

        Method createApp = CreateTools.class.getDeclaredMethod("createApp", String.class, String.class, String.class,
                String.class, String.class, String.class, Boolean.class, boolean.class, boolean.class, Integer.class);
        Tool createAppTool = createApp.getAnnotation(Tool.class);
        assertNotNull(createAppTool);
        assertEquals("quarkus_create_app", createAppTool.name());
        assertTrue(createAppTool.description().contains("create an app"),
                "alias should match natural app-creation phrasing");
    }
}
