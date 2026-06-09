package io.quarkus.agent.mcp;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * Manages Quarkus dev mode child processes.
 * Each project directory maps to at most one running instance.
 */
@ApplicationScoped
public class QuarkusProcessManager {

    private static final Logger LOG = Logger.getLogger(QuarkusProcessManager.class);

    private final ConcurrentHashMap<String, QuarkusInstance> instances = new ConcurrentHashMap<>();

    @Inject
    ManagedExecutor executor;

    @ConfigProperty(name = "agent-mcp.process.gradle-cmd")
    Optional<String> gradleCmd;

    @ConfigProperty(name = "agent-mcp.app-log.enabled")
    Optional<Boolean> appLogEnabled;

    private static final Set<String> VALID_BUILD_TOOLS = Set.of("maven", "gradle");
    static final int DEFAULT_HTTP_PORT = 8080;
    private static final int MAX_PORT_SCAN = 100;

    public synchronized Integer start(String projectDir, String buildTool, Integer httpPort, String mavenProfiles) {
        if (buildTool != null && !VALID_BUILD_TOOLS.contains(buildTool.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Invalid build tool: '" + buildTool + "'. Must be 'maven' or 'gradle'.");
        }
        if (httpPort != null && (httpPort < 1 || httpPort > 65535)) {
            throw new IllegalArgumentException(
                    "Invalid HTTP port: " + httpPort + ". Must be between 1 and 65535.");
        }
        String normalizedDir = normalize(projectDir);

        QuarkusInstance existing = instances.get(normalizedDir);
        if (existing != null) {
            if (existing.isAlive()) {
                throw new IllegalStateException("Quarkus instance already running at: " + normalizedDir);
            }
            // Clean up dead instance before starting a new one
            instances.remove(normalizedDir);
            LOG.infof("Cleaned up dead instance at: %s", normalizedDir);
        }

        Integer effectivePort = httpPort;
        if (effectivePort == null && !isPortAvailable(DEFAULT_HTTP_PORT)) {
            effectivePort = findAvailablePort(DEFAULT_HTTP_PORT);
            LOG.infof("Default port %d is in use, using port %d instead", DEFAULT_HTTP_PORT, effectivePort);
        }

        String detectedBuildTool = buildTool != null ? buildTool : detectBuildTool(normalizedDir);
        ProcessBuilder pb = createProcessBuilder(normalizedDir, detectedBuildTool, effectivePort, mavenProfiles);

        try {
            Process process = pb.start();
            QuarkusInstance instance = new QuarkusInstance(normalizedDir, detectedBuildTool, httpPort, mavenProfiles, process, executor);
            instances.put(normalizedDir, instance);
            if (appLogEnabled.orElse(false)) {
                instance.enableFileLogging(computeLogFile(normalizedDir));
            }
            LOG.infof("Started Quarkus dev mode at: %s (build tool: %s)", normalizedDir, detectedBuildTool);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Quarkus dev mode: " + e.getMessage(), e);
        }
        return effectivePort;
    }

    public synchronized void stop(String projectDir) {
        String normalizedDir = normalize(projectDir);
        QuarkusInstance instance = instances.get(normalizedDir);
        if (instance == null) {
            throw new IllegalStateException("No Quarkus instance found at: " + normalizedDir);
        }
        instance.stop();
        instances.remove(normalizedDir);
        LOG.infof("Stopped Quarkus instance at: %s", normalizedDir);
    }

    public synchronized void restart(String projectDir) {
        String normalizedDir = normalize(projectDir);
        QuarkusInstance instance = instances.get(normalizedDir);
        if (instance == null) {
            throw new IllegalStateException(
                    "No Quarkus instance found at: " + normalizedDir + ". Use quarkus_start first.");
        }

        if (!instance.isAlive()) {
            String savedBuildTool = instance.getBuildTool();
            Integer savedHttpPort = instance.getRequestedHttpPort();
            String savedMavenProfiles = instance.getMavenProfiles();
            instances.remove(normalizedDir);
            start(normalizedDir, savedBuildTool, savedHttpPort, savedMavenProfiles);
            LOG.infof("Re-started dead Quarkus instance at: %s", normalizedDir);
        } else {
            instance.restart();
            LOG.infof("Restart triggered at: %s", normalizedDir);
        }
    }

    public QuarkusInstance getInstance(String projectDir) {
        return instances.get(normalize(projectDir));
    }

    public Map<String, String> listInstances() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, QuarkusInstance> entry : instances.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getStatus().name().toLowerCase());
        }
        return result;
    }

    @PreDestroy
    void stopAll() {
        for (Map.Entry<String, QuarkusInstance> entry : instances.entrySet()) {
            try {
                entry.getValue().stop();
                LOG.infof("Stopped instance at: %s", entry.getKey());
            } catch (Exception e) {
                LOG.errorf("Error stopping instance at %s: %s", entry.getKey(), e.getMessage());
            }
        }
        instances.clear();
    }

    private String normalize(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) {
            throw new IllegalArgumentException("projectDir must not be null or empty.");
        }
        try {
            return new File(projectDir).getCanonicalPath();
        } catch (IOException e) {
            return new File(projectDir).getAbsolutePath();
        }
    }

    private String detectBuildTool(String projectDir) {
        File dir = new File(projectDir);
        if (new File(dir, "pom.xml").exists()) {
            return "maven";
        } else if (new File(dir, "build.gradle").exists() || new File(dir, "build.gradle.kts").exists()) {
            return "gradle";
        }
        throw new IllegalArgumentException(
                "Cannot detect build tool at: " + projectDir + ". No pom.xml or build.gradle found.");
    }

    private ProcessBuilder createProcessBuilder(String projectDir, String buildTool, Integer httpPort, String mavenProfiles) {
        File dir = new File(projectDir);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + projectDir);
        }

        ProcessBuilder pb;
        if ("gradle".equalsIgnoreCase(buildTool)) {
            pb = createGradleProcessBuilder(dir);
        } else {
            pb = createMavenProcessBuilder(dir, mavenProfiles);
        }

        if (httpPort != null) {
            pb.command().add("-Dquarkus.http.port=" + httpPort);
            pb.command().add("-Dquarkus.http.test-port=0");
        }

        pb.directory(dir);
        pb.redirectErrorStream(false);
        return pb;
    }

    private ProcessBuilder createMavenProcessBuilder(File projectDir, String mavenProfiles) {
        String mvnCmd = ProcessUtils.resolveMavenCommand(projectDir);
        var command = new ArrayList<>(List.of(mvnCmd, "quarkus:dev",
                "-Dquarkus.console.basic=true", "-Dquarkus.dev-mcp.enabled=true"));
        if (mavenProfiles != null && !mavenProfiles.isBlank()) {
            command.add("-P" + mavenProfiles.trim());
        }
        return new ProcessBuilder(command);
    }

    private ProcessBuilder createGradleProcessBuilder(File projectDir) {
        String cmd = gradleCmd.orElseGet(() -> ProcessUtils.resolveGradleCommand(projectDir));
        return new ProcessBuilder(cmd, "quarkusDev", "-Dquarkus.console.basic=true",
                "-Dquarkus.dev-mcp.enabled=true");
    }

    static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static int findAvailablePort(int startPort) {
        for (int port = startPort + 1; port <= startPort + MAX_PORT_SCAN; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new IllegalStateException(
                "Could not find an available port in range " + (startPort + 1) + "-" + (startPort + MAX_PORT_SCAN)
                        + ". Specify a port explicitly using the httpPort parameter.");
    }

    static Path computeLogFile(String projectDir) {
        String dirName = Path.of(projectDir).getFileName().toString();
        return Path.of(System.getProperty("user.home"), ".quarkus", "apps", dirName, "quarkus-dev.log");
    }

}
