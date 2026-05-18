package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyResolverTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearCache() {
        DependencyResolver.clearAll();
    }

    // ── Maven POM parsing (migrated from SkillReaderTest) ────────────────────

    @Test
    void parseMavenPomExtractsDirectDeps() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>1.2.0</version>
                        </dependency>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-rest</artifactId>
                            <version>3.21.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(tempDir.toFile());

        assertEquals(2, deps.size());
        assertEquals("io.quarkiverse.langchain4j", deps.get(0).groupId());
        assertEquals("quarkus-langchain4j-openai", deps.get(0).artifactId());
        assertEquals("1.2.0", deps.get(0).version());
        assertEquals("io.quarkus", deps.get(1).groupId());
    }

    @Test
    void parseMavenPomResolvesPropertyPlaceholders() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <properties>
                        <langchain4j.version>1.2.0</langchain4j.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>${langchain4j.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(tempDir.toFile());

        assertEquals(1, deps.size());
        assertEquals("1.2.0", deps.get(0).version());
    }

    @Test
    void parseMavenPomKeepsNullForBomManaged() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-rest</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(tempDir.toFile());

        assertEquals(1, deps.size());
        assertNull(deps.get(0).version());
    }

    @Test
    void parseMavenPomNullsUnresolvableProperties() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>${unknown.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(tempDir.toFile());

        assertEquals(1, deps.size());
        assertNull(deps.get(0).version());
    }

    @Test
    void parseMavenPomSkipsPluginDeps() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>io.quarkus</groupId>
                                <artifactId>quarkus-maven-plugin</artifactId>
                                <version>3.21.2</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>some.plugin</groupId>
                                        <artifactId>plugin-dep</artifactId>
                                        <version>1.0</version>
                                    </dependency>
                                </dependencies>
                            </plugin>
                        </plugins>
                    </build>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>1.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(tempDir.toFile());

        assertEquals(1, deps.size());
        assertEquals("quarkus-langchain4j-openai", deps.get(0).artifactId());
    }

    @Test
    void parseMavenPomSkipsDependencyManagement() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.quarkus.platform</groupId>
                                <artifactId>quarkus-bom</artifactId>
                                <version>3.21.2</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>1.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(tempDir.toFile());

        assertEquals(1, deps.size());
        assertEquals("quarkus-langchain4j-openai", deps.get(0).artifactId());
    }

    @Test
    void parseMavenPomReturnsEmptyForMissingPom() {
        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenPom(
                tempDir.resolve("no-such-project").toFile());

        assertTrue(deps.isEmpty());
    }

    // ── resolveProperty ──────────────────────────────────────────────────────

    @Test
    void resolvePropertyHandlesNullAndNoPlaceholders() {
        assertEquals("1.2.0", DependencyResolver.resolveProperty("1.2.0", Map.of()));
        assertNull(DependencyResolver.resolveProperty(null, Map.of()));
        assertEquals("hello", DependencyResolver.resolveProperty("hello", Map.of("foo", "bar")));
    }

    @Test
    void resolvePropertySubstitutesMultiplePlaceholders() {
        Map<String, String> props = Map.of("major", "3", "minor", "21");
        assertEquals("3.21", DependencyResolver.resolveProperty("${major}.${minor}", props));
    }

    // ── Maven dependency:list output parsing ─────────────────────────────────

    @Test
    void parseMavenDependencyListExtractsDeps() {
        String output = """
                The following files have been resolved:
                   io.quarkiverse.jberet:quarkus-jberet:jar:2.9.1:compile
                   io.quarkus:quarkus-arc:jar:3.21.2:compile
                   io.quarkus:quarkus-rest:jar:3.21.2:runtime
                """;

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenDependencyList(normalizeEOL(output));

        assertEquals(3, deps.size());
        assertEquals("io.quarkiverse.jberet", deps.get(0).groupId());
        assertEquals("quarkus-jberet", deps.get(0).artifactId());
        assertEquals("2.9.1", deps.get(0).version());
        assertEquals("io.quarkus", deps.get(1).groupId());
        assertEquals("quarkus-arc", deps.get(1).artifactId());
        assertEquals("3.21.2", deps.get(1).version());
    }

    @Test
    void parseMavenDependencyListHandlesEmptyOutput() {
        assertTrue(DependencyResolver.parseMavenDependencyList("").isEmpty());
    }

    @Test
    void parseMavenDependencyListHandlesNullOutput() {
        assertTrue(DependencyResolver.parseMavenDependencyList(null).isEmpty());
    }

    @Test
    void parseMavenDependencyListIgnoresNonDependencyLines() {
        String output = """
                The following files have been resolved:

                some random text
                   io.quarkus:quarkus-arc:jar:3.21.2:compile
                another line
                """;

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseMavenDependencyList(normalizeEOL(output));

        assertEquals(1, deps.size());
        assertEquals("quarkus-arc", deps.get(0).artifactId());
    }

    // ── Gradle dependency tree parsing ───────────────────────────────────────

    @Test
    void parseGradleDependencyTreeExtractsDirectDeps() {
        String output = """
                runtimeClasspath
                +--- io.quarkus:quarkus-rest:3.21.2
                +--- io.quarkiverse.jberet:quarkus-jberet:2.9.1
                \\--- io.quarkus:quarkus-arc:3.21.2
                """;

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseGradleDependencyTree(output);

        assertEquals(3, deps.size());
        assertEquals("io.quarkus", deps.get(0).groupId());
        assertEquals("quarkus-rest", deps.get(0).artifactId());
        assertEquals("3.21.2", deps.get(0).version());
        assertEquals("io.quarkiverse.jberet", deps.get(1).groupId());
        assertEquals("quarkus-jberet", deps.get(1).artifactId());
        assertEquals("2.9.1", deps.get(1).version());
    }

    @Test
    void parseGradleDependencyTreeHandlesArrowVersions() {
        String output = """
                runtimeClasspath
                +--- io.quarkus:quarkus-arc:3.20.0 -> 3.21.2
                """;

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseGradleDependencyTree(output);

        assertEquals(1, deps.size());
        assertEquals("3.21.2", deps.get(0).version());
    }

    @Test
    void parseGradleDependencyTreeIgnoresTransitiveDeps() {
        String output = """
                runtimeClasspath
                +--- io.quarkus:quarkus-rest:3.21.2
                |    +--- io.quarkus:quarkus-core:3.21.2
                |    \\--- io.smallrye:smallrye-common:2.0
                \\--- io.quarkus:quarkus-arc:3.21.2
                """;

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseGradleDependencyTree(output);

        assertEquals(2, deps.size());
        assertEquals("quarkus-rest", deps.get(0).artifactId());
        assertEquals("quarkus-arc", deps.get(1).artifactId());
    }

    @Test
    void parseGradleDependencyTreeStripsConstraintMarkers() {
        String output = """
                runtimeClasspath
                +--- io.quarkus:quarkus-rest:3.21.2 (c)
                +--- io.quarkus:quarkus-arc:3.21.2 (*)
                \\--- io.quarkus:quarkus-core:3.21.2 (n)
                """;

        List<DependencyResolver.Dependency> deps = DependencyResolver.parseGradleDependencyTree(output);

        assertEquals(3, deps.size());
        assertEquals("3.21.2", deps.get(0).version());
        assertEquals("3.21.2", deps.get(1).version());
        assertEquals("3.21.2", deps.get(2).version());
    }

    @Test
    void parseGradleDependencyTreeHandlesEmptyOutput() {
        assertTrue(DependencyResolver.parseGradleDependencyTree("").isEmpty());
    }

    @Test
    void parseGradleDependencyTreeHandlesNullOutput() {
        assertTrue(DependencyResolver.parseGradleDependencyTree(null).isEmpty());
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    @Test
    void resolveCachesResults() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-rest</artifactId>
                            <version>3.21.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<DependencyResolver.Dependency> first = DependencyResolver.resolve(tempDir.toString());
        Files.delete(tempDir.resolve("pom.xml"));
        List<DependencyResolver.Dependency> second = DependencyResolver.resolve(tempDir.toString());

        assertEquals(1, first.size());
        assertEquals(first, second);
    }

    @Test
    void invalidateClearsCache() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-rest</artifactId>
                            <version>3.21.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        DependencyResolver.resolve(tempDir.toString());
        Files.delete(tempDir.resolve("pom.xml"));
        DependencyResolver.invalidate(tempDir.toString());
        List<DependencyResolver.Dependency> afterInvalidate = DependencyResolver.resolve(tempDir.toString());

        assertTrue(afterInvalidate.isEmpty());
    }

    @Test
    void resolveReturnsEmptyForNullProjectDir() {
        assertTrue(DependencyResolver.resolve(null).isEmpty());
    }

    @Test
    void resolveReturnsEmptyForNonExistentDir() {
        assertTrue(DependencyResolver.resolve("/non/existent/path").isEmpty());
    }

    @Test
    void resolveReturnsEmptyForDirectoryWithoutBuildFiles() {
        assertTrue(DependencyResolver.resolve(tempDir.toString()).isEmpty());
    }

    @Test
    void parseMavenDependencyListNormal() {
        String output = """
                The following files have been resolved:
                   com.x.y.quarkus:x-my-dep:jar:1.0.0-SNAPSHOT:compile
                   io.quarkus:quarkus-datasource-common:jar:3.35.2:compile
                """;
        List<DependencyResolver.Dependency> dependencies = DependencyResolver.parseMavenDependencyList(normalizeEOL(output));
        assertEquals(2, dependencies.size());
        assertEquals("[Dependency[groupId=com.x.y.quarkus, artifactId=x-my-dep, version=1.0.0-SNAPSHOT], Dependency[groupId=io.quarkus, artifactId=quarkus-datasource-common, version=3.35.2]]", dependencies.toString());
    }

    @Test
    void parseMavenDependencyListFull() {
        String output = """
                The following files have been resolved:
                   com.x.y.quarkus:x-my-dep:jar:1.0.0-SNAPSHOT:compile[36m -- module x.my.dep[0;1;33m (auto)[m
                   io.quarkus:quarkus-datasource-common:jar:3.35.2:compile[36m -- module quarkus.datasource.common[0;1;33m (auto)[m
                   io.smallrye:smallrye-context-propagation-jta:jar:2.3.0:compile[36m -- module smallrye.context.propagation.jta[0;1;33m (auto)[m
                   jakarta.transaction:jakarta.transaction-api:jar:2.0.1:compile[36m -- module jakarta.transaction[m
                   io.smallrye.stork:stork-configuration-generator:jar:2.7.9:provided[36m -- module io.smallrye.stork.config.generator[m
                """;
        List<DependencyResolver.Dependency> dependencies = DependencyResolver.parseMavenDependencyList(normalizeEOL(output));
        assertEquals(5, dependencies.size());
        assertEquals("[Dependency[groupId=com.x.y.quarkus, artifactId=x-my-dep, version=1.0.0-SNAPSHOT], Dependency[groupId=io.quarkus, artifactId=quarkus-datasource-common, version=3.35.2], Dependency[groupId=io.smallrye, artifactId=smallrye-context-propagation-jta, version=2.3.0], Dependency[groupId=jakarta.transaction, artifactId=jakarta.transaction-api, version=2.0.1], Dependency[groupId=io.smallrye.stork, artifactId=stork-configuration-generator, version=2.7.9]]", dependencies.toString());
    }

    private String normalizeEOL(String s) {
        return s.replace("\r\n", "\n").replace("\n", System.lineSeparator());
    }
}
