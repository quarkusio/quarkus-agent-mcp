package io.quarkus.agent.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.testcontainers.DockerClientFactory;

/**
 * Checks container runtime availability and detects container-related errors
 * in Quarkus application logs. Helps AI agents diagnose Dev Services failures
 * when Docker/Podman is not running.
 */
final class ContainerRuntimeChecker {

    private static final Logger LOG = Logger.getLogger(ContainerRuntimeChecker.class);

    private static volatile Boolean cachedAvailable;
    private static volatile long cachedAt;
    private static final long CACHE_TTL_MS = 60_000;

    private static final Map<String, String> DEV_SERVICES_EXTENSIONS = Map.ofEntries(
            Map.entry("quarkus-jdbc-postgresql", "PostgreSQL"),
            Map.entry("quarkus-jdbc-mysql", "MySQL"),
            Map.entry("quarkus-jdbc-mariadb", "MariaDB"),
            Map.entry("quarkus-jdbc-mssql", "SQL Server"),
            Map.entry("quarkus-jdbc-oracle", "Oracle"),
            Map.entry("quarkus-jdbc-db2", "DB2"),
            Map.entry("quarkus-reactive-pg-client", "PostgreSQL Reactive"),
            Map.entry("quarkus-reactive-mysql-client", "MySQL Reactive"),
            Map.entry("quarkus-reactive-mssql-client", "SQL Server Reactive"),
            Map.entry("quarkus-reactive-oracle-client", "Oracle Reactive"),
            Map.entry("quarkus-mongodb", "MongoDB"),
            Map.entry("quarkus-elasticsearch", "Elasticsearch"),
            Map.entry("quarkus-opensearch", "OpenSearch"),
            Map.entry("quarkus-messaging-kafka", "Kafka"),
            Map.entry("quarkus-kafka-client", "Kafka"),
            Map.entry("quarkus-kafka-streams", "Kafka"),
            Map.entry("quarkus-smallrye-reactive-messaging-amqp", "AMQP"),
            Map.entry("quarkus-smallrye-reactive-messaging-rabbitmq", "RabbitMQ"),
            Map.entry("quarkus-redis", "Redis"),
            Map.entry("quarkus-infinispan", "Infinispan"),
            Map.entry("quarkus-keycloak", "Keycloak"),
            Map.entry("quarkus-oidc", "OIDC/Keycloak"),
            Map.entry("quarkus-apicurio", "Apicurio Registry"));

    private static final List<String> CONTAINER_ERROR_PATTERNS = List.of(
            "Could not find a valid Docker environment",
            "Cannot connect to the Docker daemon",
            "Is the docker daemon running",
            "docker: not found",
            "podman: not found",
            "ContainerLaunchException");

    private ContainerRuntimeChecker() {
    }

    static boolean isContainerRuntimeAvailable() {
        long now = System.currentTimeMillis();
        Boolean cached = cachedAvailable;
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }
        synchronized (ContainerRuntimeChecker.class) {
            if (cachedAvailable != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
                return cachedAvailable;
            }
            try {
                cachedAvailable = DockerClientFactory.instance().isDockerAvailable();
            } catch (Exception e) {
                LOG.debugf("Container runtime availability check failed: %s", e.getMessage());
                cachedAvailable = false;
            }
            cachedAt = System.currentTimeMillis();
            return cachedAvailable;
        }
    }

    static List<String> detectDevServicesExtensions(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) {
            return List.of();
        }
        String content = readBuildFileContent(Path.of(projectDir));
        if (content == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (var entry : DEV_SERVICES_EXTENSIONS.entrySet()) {
            if (content.contains(entry.getKey()) && !found.contains(entry.getValue())) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    static Optional<String> detectContainerIssues(String logText) {
        if (logText == null || logText.isEmpty()) {
            return Optional.empty();
        }
        String matchedPattern = null;
        for (String pattern : CONTAINER_ERROR_PATTERNS) {
            if (logText.contains(pattern)) {
                matchedPattern = pattern;
                break;
            }
        }
        if (matchedPattern == null && logText.contains("org.testcontainers")) {
            if (logText.contains("Could not start container")
                    || logText.contains("Timed out waiting for container")
                    || logText.contains("Container startup failed")) {
                matchedPattern = "Testcontainers container startup failure";
            }
        }
        if (matchedPattern == null) {
            return Optional.empty();
        }
        return Optional.of(
                "CONTAINER RUNTIME ISSUE DETECTED\n\n"
                        + "The application logs indicate that Docker/Podman is not running or not accessible.\n"
                        + "This project uses Quarkus Dev Services, which require a container runtime to start "
                        + "backing services (databases, message brokers, etc.) automatically.\n\n"
                        + "Detected error: " + matchedPattern + "\n\n"
                        + "ACTION REQUIRED: Ask the user to start Docker or Podman, then restart the app "
                        + "with quarkus_restart or quarkus_stop + quarkus_start.\n\n"
                        + "Do NOT attempt to fix this by modifying code, configuration, or dependencies. "
                        + "This is an infrastructure issue, not a code issue.");
    }

    private static String readBuildFileContent(Path projectDir) {
        Path pomXml = projectDir.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return readFileQuietly(pomXml);
        }
        Path buildGradleKts = projectDir.resolve("build.gradle.kts");
        if (Files.exists(buildGradleKts)) {
            return readFileQuietly(buildGradleKts);
        }
        Path buildGradle = projectDir.resolve("build.gradle");
        if (Files.exists(buildGradle)) {
            return readFileQuietly(buildGradle);
        }
        return null;
    }

    private static String readFileQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debugf("Failed to read %s: %s", file, e.getMessage());
            return null;
        }
    }

    static String containerWarning(String projectDir) {
        try {
            if (!isContainerRuntimeAvailable()) {
                List<String> extensions = detectDevServicesExtensions(projectDir);
                if (!extensions.isEmpty()) {
                    return "\n\nWARNING: Docker/Podman is not running. "
                            + "This project uses extensions that require Dev Services containers: "
                            + String.join(", ", extensions) + ". "
                            + "The app will likely fail to start backing services. "
                            + "Ask the user to start Docker/Podman, then restart the app with quarkus_stop + quarkus_start. "
                            + "Do NOT attempt to fix this by changing code or configuration.";
                }
            }
        } catch (Exception e) {
            LOG.debugf("Container runtime check failed: %s", e.getMessage());
        }
        return "";
    }

    // Visible for testing
    static void clearCache() {
        synchronized (ContainerRuntimeChecker.class) {
            cachedAvailable = null;
            cachedAt = 0;
        }
    }
}
