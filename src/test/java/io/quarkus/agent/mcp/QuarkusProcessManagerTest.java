package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for QuarkusProcessManager validation and build tool detection.
 * These tests focus on the validation/detection logic without actually
 * starting Quarkus processes (which would require a real project).
 */
class QuarkusProcessManagerTest {

    @TempDir
    Path tempDir;

    private QuarkusProcessManager manager;

    @BeforeEach
    void setUp() {
        manager = new QuarkusProcessManager();
    }

    @Test
    void startThrowsForNullProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.start(null, null));
    }

    @Test
    void startThrowsForEmptyProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.start("", null));
    }

    @Test
    void startThrowsForBlankProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.start("   ", null));
    }

    @Test
    void stopThrowsForNullProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.stop(null));
    }

    @Test
    void stopThrowsForNonExistentInstance() {
        assertThrows(IllegalStateException.class, () -> manager.stop("/nonexistent"));
    }

    @Test
    void restartThrowsForNonExistentInstance() {
        assertThrows(IllegalStateException.class, () -> manager.restart("/nonexistent"));
    }

    @Test
    void getInstanceReturnsNullForUnknownProject() {
        assertNull(manager.getInstance("/unknown/project"));
    }

    @Test
    void listInstancesEmptyByDefault() {
        Map<String, String> instances = manager.listInstances();
        assertTrue(instances.isEmpty());
    }

    @Test
    void throwsWhenNoBuildToolDetected() {
        // tempDir has no pom.xml or build.gradle
        assertThrows(IllegalArgumentException.class,
                () -> manager.start(tempDir.toString(), null));
    }

    @Test
    void throwsForNonDirectoryPath() throws IOException {
        Path file = tempDir.resolve("afile.txt");
        Files.writeString(file, "not a directory");

        assertThrows(IllegalArgumentException.class,
                () -> manager.start(file.toString(), "maven"));
    }

    @Test
    void detectsBuildToolMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        // Use reflection to test detectBuildTool directly
        Method m = QuarkusProcessManager.class.getDeclaredMethod("detectBuildTool", String.class);
        m.setAccessible(true);
        assertEquals("maven", m.invoke(manager, tempDir.toString()));
    }

    @Test
    void detectsBuildToolGradle() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "// gradle");
        Method m = QuarkusProcessManager.class.getDeclaredMethod("detectBuildTool", String.class);
        m.setAccessible(true);
        assertEquals("gradle", m.invoke(manager, tempDir.toString()));
    }

    @Test
    void detectsBuildToolGradleKts() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// gradle kts");
        Method m = QuarkusProcessManager.class.getDeclaredMethod("detectBuildTool", String.class);
        m.setAccessible(true);
        assertEquals("gradle", m.invoke(manager, tempDir.toString()));
    }

    @Test
    void normalizesPathsConsistently() throws Exception {
        Method m = QuarkusProcessManager.class.getDeclaredMethod("normalize", String.class);
        m.setAccessible(true);

        String path1 = (String) m.invoke(manager, tempDir.toString());
        String path2 = (String) m.invoke(manager, tempDir.toString() + "/./");

        assertEquals(path1, path2);
    }
}
