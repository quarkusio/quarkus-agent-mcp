package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerRuntimeCheckerTest {

    @TempDir
    Path tempDir;

    // --- detectDevServicesExtensions ---

    @Test
    void detectsPostgresqlAndKafkaInMaven() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-jdbc-postgresql</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-messaging-kafka</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertTrue(result.contains("PostgreSQL"));
        assertTrue(result.contains("Kafka"));
    }

    @Test
    void detectsRedisInGradleKts() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("io.quarkus:quarkus-redis")
                }
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertTrue(result.contains("Redis"));
    }

    @Test
    void detectsMongodbInGradle() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                dependencies {
                    implementation 'io.quarkus:quarkus-mongodb-client'
                }
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertTrue(result.contains("MongoDB"));
    }

    @Test
    void returnsEmptyForRestOnlyProject() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-rest-jackson</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoBuildFile() {
        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForNullProjectDir() {
        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForBlankProjectDir() {
        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void detectsMultipleExtensions() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>quarkus-jdbc-postgresql</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>quarkus-redis</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>quarkus-elasticsearch</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertEquals(3, result.size());
        assertTrue(result.contains("PostgreSQL"));
        assertTrue(result.contains("Redis"));
        assertTrue(result.contains("Elasticsearch"));
    }

    @Test
    void ignoresExtensionNamesInComments() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <!-- TODO: add kafka and redis support later -->
                    <dependencies>
                        <dependency>
                            <artifactId>quarkus-rest-jackson</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    void deduplicatesMultipleKafkaExtensions() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <artifactId>quarkus-messaging-kafka</artifactId>
                        </dependency>
                        <dependency>
                            <artifactId>quarkus-kafka-client</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<String> result = ContainerRuntimeChecker.detectDevServicesExtensions(tempDir.toString());
        assertEquals(1, result.size());
        assertEquals("Kafka", result.get(0));
    }

    // --- detectContainerIssues ---

    @Test
    void detectsDockerEnvironmentError() {
        String logs = "2024-01-01 ERROR Could not find a valid Docker environment";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("CONTAINER RUNTIME ISSUE DETECTED"));
        assertTrue(result.get().contains("Could not find a valid Docker environment"));
    }

    @Test
    void detectsDockerDaemonNotRunning() {
        String logs = "Cannot connect to the Docker daemon at unix:///var/run/docker.sock";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Cannot connect to the Docker daemon"));
    }

    @Test
    void detectsIsDockerDaemonRunning() {
        String logs = "Is the docker daemon running?";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
    }

    @Test
    void detectsContainerLaunchException() {
        String logs = "org.testcontainers.containers.ContainerLaunchException: failed to start";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("ContainerLaunchException"));
    }

    @Test
    void detectsDockerNotFound() {
        String logs = "docker: not found";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
    }

    @Test
    void detectsPodmanNotFound() {
        String logs = "podman: not found";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
    }

    @Test
    void detectsTestcontainersStartupFailure() {
        String logs = """
                2024-01-01 INFO Starting
                org.testcontainers.containers.GenericContainer - Could not start container
                """;
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Testcontainers container startup failure"));
    }

    @Test
    void ignoresNormalTestcontainersLogs() {
        String logs = """
                2024-01-01 INFO org.testcontainers.DockerClientFactory - Docker client strategy found
                2024-01-01 INFO org.testcontainers.utility.ImageNameSubstitutor - Image name substitution
                """;
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForCleanLogs() {
        String logs = """
                2024-01-01 INFO Quarkus started
                2024-01-01 INFO Listening on: http://0.0.0.0:8080
                2024-01-01 INFO installed features: [cdi, rest]
                """;
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForNullLogs() {
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForEmptyLogs() {
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues("");
        assertTrue(result.isEmpty());
    }

    @Test
    void diagnosticIncludesActionGuidance() {
        String logs = "Could not find a valid Docker environment";
        Optional<String> result = ContainerRuntimeChecker.detectContainerIssues(logs);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Ask the user to start Docker or Podman"));
        assertTrue(result.get().contains("Do NOT attempt to fix this by modifying code"));
    }
}
