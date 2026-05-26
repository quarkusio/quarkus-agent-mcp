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
    void accepts999SnapshotVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <quarkus.platform.version>999-SNAPSHOT</quarkus.platform.version>
                    </properties>
                </project>
                """);

        assertEquals("999-SNAPSHOT", QuarkusVersionDetector.detect(tempDir.toString()));
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

    // --- Maven evaluate output parsing ---

    @Test
    void parseMavenEvaluateOutputExtractsVersion() {
        assertEquals("3.35.2", QuarkusVersionDetector.parseMavenEvaluateOutput("3.35.2\n"));
    }

    @Test
    void parseMavenEvaluateOutputReturnsNullForNullLiteral() {
        assertNull(QuarkusVersionDetector.parseMavenEvaluateOutput("null\n"));
    }

    @Test
    void parseMavenEvaluateOutputReturnsNullForUnresolved() {
        assertNull(QuarkusVersionDetector.parseMavenEvaluateOutput("${quarkus.platform.version}"));
    }

    @Test
    void parseMavenEvaluateOutputReturnsNullForEmpty() {
        assertNull(QuarkusVersionDetector.parseMavenEvaluateOutput(""));
    }

    @Test
    void parseMavenEvaluateOutputReturnsNullForNull() {
        assertNull(QuarkusVersionDetector.parseMavenEvaluateOutput(null));
    }

    @Test
    void parseMavenEvaluateOutputTrimsWhitespace() {
        assertEquals("3.35.2", QuarkusVersionDetector.parseMavenEvaluateOutput("  3.35.2  \n"));
    }

    // --- Gradle properties output parsing ---

    @Test
    void parseGradlePropertiesOutputExtractsVersion() {
        assertEquals("3.35.2", QuarkusVersionDetector.parseGradlePropertiesOutput("quarkusPlatformVersion: 3.35.2\n"));
    }

    @Test
    void parseGradlePropertiesOutputReturnsNullWhenMissing() {
        assertNull(QuarkusVersionDetector.parseGradlePropertiesOutput("otherProp: value\n"));
    }

    @Test
    void parseGradlePropertiesOutputReturnsNullForEmpty() {
        assertNull(QuarkusVersionDetector.parseGradlePropertiesOutput(""));
    }

    @Test
    void parseGradlePropertiesOutputReturnsNullForNull() {
        assertNull(QuarkusVersionDetector.parseGradlePropertiesOutput(null));
    }

    @Test
    void parseGradlePropertiesOutputHandlesNoSpace() {
        assertEquals("3.35.2", QuarkusVersionDetector.parseGradlePropertiesOutput("quarkusPlatformVersion:3.35.2"));
    }

    @Test
    void parseGradlePropertiesOutputHandlesMultipleLines() {
        String output = """
                someOtherProperty: foo
                quarkusPlatformVersion: 3.30.1
                anotherProperty: bar
                """;
        assertEquals("3.30.1", QuarkusVersionDetector.parseGradlePropertiesOutput(output));
    }

    // --- Fallback: quarkus-core dependency in pom.xml ---

    @Test
    void detectFromQuarkusCoreDependency() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-core</artifactId>
                      <version>3.21.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertEquals("3.21.2", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void detectFromQuarkusCoreDependencyWithPropertyResolution() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <properties>
                    <quarkus.version>3.21.2</quarkus.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-core</artifactId>
                      <version>${quarkus.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertEquals("3.21.2", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void platformVersionTakesPrecedenceOverQuarkusCore() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <properties>
                    <quarkus.platform.version>3.21.2</quarkus.platform.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-core</artifactId>
                      <version>3.20.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertEquals("3.21.2", QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void quarkusCoreFallbackIgnoresUnresolvedProperty() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-core</artifactId>
                      <version>${unresolved.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void quarkusCoreFallbackIgnoresPluginDependencies() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <dependencies>
                          <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-core</artifactId>
                            <version>3.21.2</version>
                          </dependency>
                        </dependencies>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void quarkusCoreFallbackIgnoresDependencyManagement() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.quarkus</groupId>
                        <artifactId>quarkus-core</artifactId>
                        <version>3.21.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    @Test
    void quarkusCoreFallbackIgnoresDifferentGroupId() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.custom</groupId>
                      <artifactId>quarkus-core</artifactId>
                      <version>3.21.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertNull(QuarkusVersionDetector.detect(tempDir.toString()));
    }

    // --- Maven dependency:list output parsing for quarkus-core ---

    @Test
    void parseMavenDependencyListForQuarkusCoreExtractsVersion() {
        String output = """
                   io.quarkus:quarkus-core:jar:3.21.2:compile
                   io.quarkus:quarkus-arc:jar:3.21.2:compile
                """;
        assertEquals("3.21.2", QuarkusVersionDetector.parseMavenDependencyListForQuarkusCore(output));
    }

    @Test
    void parseMavenDependencyListForQuarkusCoreReturnsNullWhenMissing() {
        String output = """
                   io.quarkus:quarkus-arc:jar:3.21.2:compile
                """;
        assertNull(QuarkusVersionDetector.parseMavenDependencyListForQuarkusCore(output));
    }

    @Test
    void parseMavenDependencyListForQuarkusCoreReturnsNullForNull() {
        assertNull(QuarkusVersionDetector.parseMavenDependencyListForQuarkusCore(null));
    }

    @Test
    void parseMavenDependencyListForQuarkusCoreReturnsNullForEmpty() {
        assertNull(QuarkusVersionDetector.parseMavenDependencyListForQuarkusCore(""));
    }

    @Test
    void parseMavenDependencyListForQuarkusCoreHandlesAnsiEscape() {
        String output = " io.quarkus:quarkus-core:jar:3.21.2:compile\u001B[36m -- module io.quarkus\u001B[m";
        assertEquals("3.21.2", QuarkusVersionDetector.parseMavenDependencyListForQuarkusCore(output));
    }

    @Test
    void parseMavenDependencyListForQuarkusCoreHandlesWindowsLineEndings() {
        String output = "   io.quarkus:quarkus-arc:jar:3.21.2:compile\r\n   io.quarkus:quarkus-core:jar:3.21.2:compile\r\n";
        assertEquals("3.21.2", QuarkusVersionDetector.parseMavenDependencyListForQuarkusCore(output));
    }

    // --- Gradle dependency tree output parsing for quarkus-core ---

    @Test
    void parseGradleDependencyTreeForQuarkusCoreExtractsVersion() {
        String output = """
                +--- io.quarkus:quarkus-core:3.21.2
                +--- io.quarkus:quarkus-arc:3.21.2
                """;
        assertEquals("3.21.2", QuarkusVersionDetector.parseGradleDependencyTreeForQuarkusCore(output));
    }

    @Test
    void parseGradleDependencyTreeForQuarkusCoreResolvesVersionOverride() {
        String output = "+--- io.quarkus:quarkus-core:3.20.0 -> 3.21.2";
        assertEquals("3.21.2", QuarkusVersionDetector.parseGradleDependencyTreeForQuarkusCore(output));
    }

    @Test
    void parseGradleDependencyTreeForQuarkusCoreReturnsNullWhenMissing() {
        String output = """
                +--- io.quarkus:quarkus-arc:3.21.2
                """;
        assertNull(QuarkusVersionDetector.parseGradleDependencyTreeForQuarkusCore(output));
    }

    @Test
    void parseGradleDependencyTreeForQuarkusCoreReturnsNullForNull() {
        assertNull(QuarkusVersionDetector.parseGradleDependencyTreeForQuarkusCore(null));
    }

    @Test
    void parseGradleDependencyTreeForQuarkusCoreReturnsNullForEmpty() {
        assertNull(QuarkusVersionDetector.parseGradleDependencyTreeForQuarkusCore(""));
    }
}
