package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusVersionDetectorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearCache() throws Exception {
        // Clear the static cache between tests via reflection
        var field = QuarkusVersionDetector.class.getDeclaredField("VERSION_CACHE");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<?, ?>) field.get(null)).clear();
    }

    @Test
    void detectFromMavenPlatformVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>3.21.2</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertEquals("3.21.2", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void detectFromMavenPluginVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus-plugin.version>3.15.0</quarkus-plugin.version>
                    </properties>
                </project>
                """);

        assertEquals("3.15.0", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void detectFromGradleProperties() throws IOException {
        Files.writeString(tempDir.resolve("gradle.properties"), """
                quarkusPlatformVersion=3.30.1
                quarkusPlatformGroup=io.quarkus.platform
                """);

        assertEquals("3.30.1", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void mavenTakesPrecedenceOverGradle() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>3.21.2</quarkus.platform.version>
                    </properties>
                </project>
                """);
        Files.writeString(tempDir.resolve("gradle.properties"), """
                quarkusPlatformVersion=3.30.1
                """);

        assertEquals("3.21.2", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void rejectsPropertyReference() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>${some.version}</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void rejectsMaliciousVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>latest && rm -rf /</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void rejectsRegistryInjection() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>evil.com/backdoor:latest</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void acceptsSnapshotVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>3.32.0-SNAPSHOT</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertEquals("3.32.0-SNAPSHOT", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void acceptsFinalVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>3.21.2.Final</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertEquals("3.21.2.Final", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void acceptsCRVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>3.21.0.CR1</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertEquals("3.21.0.CR1", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void returnsNullForNullProjectDir() {
        assertNull(QuarkusVersionDetector.detect(null));
    }

    @Test
    void returnsNullForEmptyProjectDir() {
        assertNull(QuarkusVersionDetector.detect(""));
    }

    @Test
    void returnsNullForBlankProjectDir() {
        assertNull(QuarkusVersionDetector.detect("   "));
    }

    @Test
    void returnsNullForNonExistentDirectory() {
        assertNull(QuarkusVersionDetector.detect("/non/existent/path"));
    }

    @Test
    void returnsNullForDirectoryWithoutBuildFiles() {
        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void returnsNullForPomWithoutQuarkusVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void cachesResult() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>3.21.2</quarkus.platform.version>
                    </properties>
                </project>
                """);

        String first = QuarkusVersionDetector.detect(tempDir.toString());
        // Delete the file — cached result should still be returned
        Files.delete(tempDir.resolve("pom.xml"));
        String second = QuarkusVersionDetector.detect(tempDir.toString());

        assertEquals("3.21.2", first);
        assertEquals("3.21.2", second);
    }

    @Test
    void handlesGradleWithSpacesAroundEquals() throws IOException {
        Files.writeString(tempDir.resolve("gradle.properties"), """
                quarkusPlatformVersion = 3.30.1
                """);

        assertEquals("3.30.1", QuarkusVersionDetector.detect(tempDir.toString()));
    }
}
