package io.quarkus.agent.mcp;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Manages pgvector containers for Quarkus documentation search.
 * Uses a generic pgvector image — documentation data is loaded from SQL fragments
 * discovered in extension deployment JARs by {@link RagSqlLoader}.
 * <p>
 * Containers are version-specific (each Quarkus version gets its own container)
 * and reusable across MCP server restarts.
 */
@ApplicationScoped
public class ContainerManager {

    private static final Logger LOG = Logger.getLogger(ContainerManager.class);

    @ConfigProperty(name = "agent-mcp.doc-search.image", defaultValue = "pgvector/pgvector:pg17")
    String image;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-user", defaultValue = "quarkus")
    String pgUser;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-password", defaultValue = "quarkus")
    String pgPassword;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-database", defaultValue = "quarkus")
    String pgDatabase;

    @Inject
    RagSqlLoader ragSqlLoader;

    private final ConcurrentHashMap<String, GenericContainer<?>> containers = new ConcurrentHashMap<>();
    private volatile Boolean dockerAvailable;
    private volatile boolean defaultWarmupStarted;
    private volatile boolean defaultWarmupDone;
    private volatile String defaultWarmupError;

    /**
     * Starts the default container in a background thread so the first searchDocs
     * call doesn't block for container startup.
     */
    public void warmUpDefaultAsync() {
        if (defaultWarmupStarted) {
            return;
        }
        defaultWarmupStarted = true;
        Thread.ofVirtual().name("container-warmup").start(() -> {
            try {
                ensureRunning(null, null);
                defaultWarmupDone = true;
            } catch (Exception e) {
                LOG.warn("Background container warm-up failed: " + e.getMessage());
                defaultWarmupError = e.getMessage();
                defaultWarmupDone = true;
            }
        });
    }

    public boolean isDefaultReady() {
        return defaultWarmupDone && defaultWarmupError == null;
    }

    public boolean isDefaultWarmupDone() {
        return defaultWarmupDone;
    }

    public String getDefaultWarmupError() {
        return defaultWarmupError;
    }

    /**
     * Ensure a pgvector container is running for the given Quarkus version.
     * Starts a generic pgvector container if needed, then loads RAG SQL fragments.
     *
     * @param quarkusVersion the Quarkus version for docs, or null for default
     * @param projectDir     the project directory for non-core extension discovery, or null
     */
    public synchronized void ensureRunning(String quarkusVersion, String projectDir) {
        checkDockerAvailable();

        String versionKey = quarkusVersion != null ? quarkusVersion : "default";

        GenericContainer<?> existing = containers.get(versionKey);
        if (existing != null && existing.isRunning()) {
            return;
        }

        try {
            startContainer(versionKey);
            loadRagData(versionKey, quarkusVersion, projectDir);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to start documentation container (" + image + "). "
                            + "Ensure Docker/Podman is running. Error: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Loads any new RAG SQL fragments into an already-running container.
     * Called after extensions are added to a project to pick up their docs.
     */
    public void loadIncrementalRagData(String quarkusVersion, String projectDir) {
        String versionKey = quarkusVersion != null ? quarkusVersion : "default";
        GenericContainer<?> container = containers.get(versionKey);
        if (container == null || !container.isRunning()) {
            LOG.debugf("No running container for version %s — skipping incremental RAG load", versionKey);
            return;
        }

        ragSqlLoader.ensureLoaded(
                quarkusVersion, projectDir,
                container.getHost(), container.getMappedPort(5432),
                pgDatabase, pgUser, pgPassword);
    }

    /**
     * Returns the host port mapped to PostgreSQL's 5432 inside the container.
     */
    public int getMappedPort(String quarkusVersion) {
        GenericContainer<?> container = getContainer(quarkusVersion);
        return container.getMappedPort(5432);
    }

    /**
     * Returns the host where the container is accessible.
     */
    public String getHost(String quarkusVersion) {
        GenericContainer<?> container = getContainer(quarkusVersion);
        return container.getHost();
    }

    private void checkDockerAvailable() {
        if (dockerAvailable == null) {
            try {
                dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
            } catch (Exception e) {
                LOG.debugf("Docker availability check failed: %s", e.getMessage());
                dockerAvailable = false;
            }
            if (dockerAvailable) {
                LOG.info("Docker/Podman detected — documentation search is available");
            } else {
                LOG.info("Docker/Podman not available — documentation search will be disabled");
            }
        }
        if (!dockerAvailable) {
            throw new RuntimeException(
                    "Documentation search requires Docker or Podman, but neither is available. "
                            + "Install Docker (https://docs.docker.com/get-docker/) or Podman, "
                            + "then restart the MCP server. All other Quarkus tools work without Docker.");
        }
    }

    private void startContainer(String versionKey) {
        LOG.infof("Starting pgvector container for Quarkus %s docs (%s)...", versionKey, image);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", pgUser)
                .withEnv("POSTGRES_PASSWORD", pgPassword)
                .withEnv("POSTGRES_DB", pgDatabase)
                .withReuse(true)
                .withLabel("quarkus-agent-mcp", "doc-search")
                .withLabel("quarkus-agent-mcp.version", versionKey)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

        container.start();
        containers.put(versionKey, container);
        LOG.infof("pgvector container started for Quarkus %s (mapped port: %d)",
                versionKey, container.getMappedPort(5432));
    }

    private void loadRagData(String versionKey, String quarkusVersion, String projectDir) {
        GenericContainer<?> container = containers.get(versionKey);
        ragSqlLoader.ensureLoaded(
                quarkusVersion, projectDir,
                container.getHost(), container.getMappedPort(5432),
                pgDatabase, pgUser, pgPassword);
    }

    private GenericContainer<?> getContainer(String quarkusVersion) {
        String versionKey = quarkusVersion != null ? quarkusVersion : "default";
        GenericContainer<?> container = containers.get(versionKey);
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException(
                    "pgvector container is not running for version " + versionKey + ". Call ensureRunning() first.");
        }
        return container;
    }

    /**
     * Releases container references without stopping them — containers use {@code withReuse(true)}
     * so they persist across MCP server restarts.
     */
    @PreDestroy
    void releaseContainerReferences() {
        for (var entry : containers.entrySet()) {
            try {
                LOG.infof("Releasing container reference for version %s (containerId: %s)",
                        entry.getKey(), entry.getValue().getContainerId());
            } catch (Exception e) {
                LOG.debugf("Error during container cleanup for %s: %s", entry.getKey(), e.getMessage());
            }
        }
        containers.clear();
    }
}
