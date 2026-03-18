package io.quarkus.agent.mcp;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
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

    /**
     * Ensure a pgvector container is running for the given Quarkus version.
     * Starts it via Testcontainers if needed.
     *
     * @param quarkusVersion the Quarkus version for docs, or null for default
     */
    public synchronized void ensureRunning(String quarkusVersion) {
        String tag = resolveImageTag(quarkusVersion);
        String key = tag;

        GenericContainer<?> existing = containers.get(key);
        if (existing != null && existing.isRunning()) {
            return;
        }

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

        try {
            container.start();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to start pgvector container for Quarkus " + tag
                            + " (image: " + image + "). "
                            + "Ensure the image exists and Docker/Podman is running. "
                            + "Error: " + e.getMessage(),
                    e);
        }
        containers.put(key, container);

        LOG.infof("pgvector container started for Quarkus %s (mapped port: %d)", tag, container.getMappedPort(5432));
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

    private GenericContainer<?> getContainer(String quarkusVersion) {
        String tag = resolveImageTag(quarkusVersion);
        GenericContainer<?> container = containers.get(tag);
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException(
                    "pgvector container is not running for version " + tag + ". Call ensureRunning() first.");
        }
        return container;
    }

    private String resolveImageTag(String quarkusVersion) {
        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            return quarkusVersion;
        }
        return defaultImageTag;
    }
}
