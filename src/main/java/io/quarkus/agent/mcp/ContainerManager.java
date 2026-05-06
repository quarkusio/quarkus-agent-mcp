package io.quarkus.agent.mcp;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Manages pgvector containers with pre-indexed Quarkus documentation.
 * Uses Testcontainers for Docker/Podman abstraction.
 * Supports version-specific containers — each Quarkus version gets its own container
 * with documentation matching that version.
 * Containers are reusable — they persist across MCP server restarts.
 */
@ApplicationScoped
public class ContainerManager {

    private static final Logger LOG = Logger.getLogger(ContainerManager.class);

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

    private final ConcurrentHashMap<String, GenericContainer<?>> containers = new ConcurrentHashMap<>();
    private final Set<String> fallbackVersions = ConcurrentHashMap.newKeySet();
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
                ensureRunning(null);
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
     * Starts it via Testcontainers if needed. If the version-specific image
     * is not available, falls back to the default image tag.
     *
     * @param quarkusVersion the Quarkus version for docs, or null for default
     */
    public synchronized void ensureRunning(String quarkusVersion) {
        checkDockerAvailable();

        String tag = resolveImageTag(quarkusVersion);

        GenericContainer<?> existing = containers.get(tag);
        if (existing != null && existing.isRunning()) {
            return;
        }

        try {
            startContainer(tag);
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
            startContainer(defaultImageTag);
            containers.put(tag, containers.get(defaultImageTag));
            fallbackVersions.add(tag);
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

    /**
     * Returns true if the given version fell back to the default image tag.
     */
    public boolean isUsingFallback(String quarkusVersion) {
        String tag = resolveImageTag(quarkusVersion);
        return fallbackVersions.contains(tag);
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

    private void startContainer(String tag) {
        String image = imagePrefix + ":" + tag;
        LOG.infof("Starting pgvector container with Quarkus %s docs (%s)...", tag, image);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", pgUser)
                .withEnv("POSTGRES_PASSWORD", pgPassword)
                .withEnv("POSTGRES_DB", pgDatabase)
                .withReuse(true)
                .withLabel("quarkus-agent-mcp", "doc-search")
                .withLabel("quarkus-agent-mcp.version", tag)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

        container.start();
        containers.put(tag, container);
        LOG.infof("pgvector container started for Quarkus %s (mapped port: %d)", tag, container.getMappedPort(5432));
    }

    private GenericContainer<?> getContainer(String quarkusVersion) {
        String tag = resolveImageTag(quarkusVersion);
        GenericContainer<?> container = containers.get(tag);
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException(
                    "pgvector container is not running for version " + tag + ". Call ensureRunning() first.");
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

    private String resolveImageTag(String quarkusVersion) {
        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            return quarkusVersion;
        }
        return defaultImageTag;
    }
}
