package io.quarkus.agent.mcp;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Manages pgvector containers for Quarkus documentation search.
 * <p>
 * For Quarkus 3.36+ (which ships RAG SQL artifacts), uses a generic pgvector image
 * with documentation loaded from SQL fragments by {@link RagSqlLoader}.
 * For older versions, falls back to the pre-built {@code chappie-ingestion-quarkus} image
 * with documentation baked in.
 * <p>
 * Containers are version-specific (each Quarkus version gets its own container)
 * and reusable across MCP server restarts.
 */
@ApplicationScoped
public class ContainerManager {

    private static final Logger LOG = Logger.getLogger(ContainerManager.class);
    static final int RAG_SQL_MIN_MINOR = 36;
    static final int RAG_SQL_MIN_PATCH_AT_36 = 1;

    @ConfigProperty(name = "agent-mcp.doc-search.image", defaultValue = "pgvector/pgvector:pg17")
    String image;

    @ConfigProperty(name = "agent-mcp.doc-search.image-prefix", defaultValue = "ghcr.io/quarkusio/chappie-ingestion-quarkus")
    String imagePrefix;

    @ConfigProperty(name = "agent-mcp.doc-search.image-tag", defaultValue = "latest")
    String defaultImageTag;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-user", defaultValue = "quarkus")
    String pgUser;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-password", defaultValue = "quarkus")
    String pgPassword;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-database", defaultValue = "quarkus")
    String pgDatabase;

    @Inject
    RagSqlLoader ragSqlLoader;

    private final ConcurrentHashMap<String, GenericContainer<?>> containers = new ConcurrentHashMap<>();
    private final Set<String> fallbackVersions = ConcurrentHashMap.newKeySet();
    private final Set<String> ragSqlVersions = ConcurrentHashMap.newKeySet();
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
                LOG.info("Documentation search is ready");
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
     * For 3.36+, starts a generic pgvector container and loads RAG SQL fragments.
     * For older versions, starts the pre-built chappie-ingestion image.
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

        if (supportsRagSql(quarkusVersion)) {
            try {
                startGenericContainer(versionKey);
                loadRagData(versionKey, quarkusVersion, projectDir);
                ragSqlVersions.add(versionKey);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to start documentation container (" + image + "). "
                                + "Ensure Docker/Podman is running. Error: " + e.getMessage(),
                        e);
            }
        } else {
            startLegacyContainer(versionKey, quarkusVersion);
            loadNonCoreRagData(versionKey, quarkusVersion, projectDir);
        }
    }

    /**
     * Returns true if the given version fell back to the default image tag
     * (only applicable to the legacy pre-built image path).
     */
    public boolean isUsingFallback(String quarkusVersion) {
        String versionKey = quarkusVersion != null ? quarkusVersion : "default";
        return fallbackVersions.contains(versionKey);
    }

    /**
     * Loads any new RAG SQL fragments into an already-running container.
     * Called after extensions are added to a project to pick up their docs.
     * For legacy containers, only non-core extension docs are loaded (core docs are baked in).
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

    public String getEmbeddingHost() {
        GenericContainer<?> container = getContainer(null);
        return container.getHost();
    }

    public int getEmbeddingPort() {
        GenericContainer<?> container = getContainer(null);
        return container.getMappedPort(9222);
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

    static boolean supportsRagSql(String version) {
        if (version == null || version.isBlank()) {
            return true;
        }
        if (version.contains("SNAPSHOT")) {
            return true;
        }
        try {
            String[] parts = version.split("[.\\-]");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (major > 3 || (major == 3 && minor > RAG_SQL_MIN_MINOR)) {
                return true;
            }
            if (major == 3 && minor == RAG_SQL_MIN_MINOR) {
                int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                return patch >= RAG_SQL_MIN_PATCH_AT_36;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void startGenericContainer(String versionKey) {
        LOG.infof("Starting pgvector container for Quarkus %s docs (%s)...", versionKey, image);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(5432, 9222)
                .withEnv("POSTGRES_USER", pgUser)
                .withEnv("POSTGRES_PASSWORD", pgPassword)
                .withEnv("POSTGRES_DB", pgDatabase)
                .withReuse(true)
                .withLabel("quarkus-agent-mcp", "doc-search")
                .withLabel("quarkus-agent-mcp.version", versionKey)
                .waitingFor(new org.testcontainers.containers.wait.strategy.WaitAllStrategy()
                        .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                        .withStrategy(Wait.forHttp("/health").forPort(9222).forStatusCode(200))
                        .withStartupTimeout(java.time.Duration.ofMinutes(3)));

        container.start();
        containers.put(versionKey, container);
        LOG.infof("pgvector container started for Quarkus %s (mapped port: %d)",
                versionKey, container.getMappedPort(5432));
    }

    private void startLegacyContainer(String versionKey, String quarkusVersion) {
        String tag = quarkusVersion != null ? quarkusVersion : defaultImageTag;

        try {
            startLegacyImage(versionKey, tag);
            return;
        } catch (Exception e) {
            if (tag.equals(defaultImageTag)) {
                throw new RuntimeException(
                        "Failed to start documentation container (image: " + imagePrefix + ":" + tag + "). "
                                + "Ensure Docker/Podman is running. Error: " + e.getMessage(),
                        e);
            }
            LOG.warnf(e, "Failed to start documentation image %s:%s, falling back to %s:%s",
                    imagePrefix, tag, imagePrefix, defaultImageTag);
        }

        try {
            startLegacyImage(versionKey, defaultImageTag);
            fallbackVersions.add(versionKey);
            LOG.infof("Using '%s' docs instead of '%s' — docs may not exactly match your Quarkus version",
                    defaultImageTag, tag);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to start documentation container for Quarkus " + tag
                            + ". Tried version-specific image and fallback (" + defaultImageTag + "). "
                            + "Error: " + e.getMessage(),
                    e);
        }
    }

    private void startLegacyImage(String versionKey, String tag) {
        String fullImage = imagePrefix + ":" + tag;
        LOG.infof("Starting pgvector container with Quarkus %s docs (%s)...", versionKey, fullImage);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(fullImage))
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

    private void loadNonCoreRagData(String versionKey, String quarkusVersion, String projectDir) {
        if (projectDir == null) {
            return;
        }
        GenericContainer<?> container = containers.get(versionKey);
        if (container == null || !container.isRunning()) {
            return;
        }
        try {
            ragSqlLoader.ensureLoaded(
                    quarkusVersion, projectDir,
                    container.getHost(), container.getMappedPort(5432),
                    pgDatabase, pgUser, pgPassword);
        } catch (Exception e) {
            LOG.warnf("Failed to load non-core extension docs: %s", e.getMessage());
        }
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
