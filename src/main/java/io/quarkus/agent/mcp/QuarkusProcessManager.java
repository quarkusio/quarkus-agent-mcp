package io.quarkus.agent.mcp;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    @ConfigProperty(name = "agent-mcp.process.mvn-cmd")
    Optional<String> mvnCmd;

    @ConfigProperty(name = "agent-mcp.process.gradle-cmd")
    Optional<String> gradleCmd;

    @ConfigProperty(name = "agent-mcp.app-log.enabled")
    Optional<Boolean> appLogEnabled;

    private static final Set<String> VALID_BUILD_TOOLS = Set.of("maven", "gradle");
    static final int DEFAULT_HTTP_PORT = 8080;
    private static final int MAX_PORT_SCAN = 100;

    public synchronized void start(String projectDir, String buildTool, Integer httpPort) {
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
        ProcessBuilder pb = createProcessBuilder(normalizedDir, detectedBuildTool, effectivePort);

        try {
            Process process = pb.start();
            QuarkusInstance instance = new QuarkusInstance(normalizedDir, process, executor);
            instances.put(normalizedDir, instance);
            if (appLogEnabled.orElse(false)) {
                instance.enableFileLogging(computeLogFile(normalizedDir));
            }
            LOG.infof("Started Quarkus dev mode at: %s (build tool: %s)", normalizedDir, detectedBuildTool);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Quarkus dev mode: " + e.getMessage(), e);
        }
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
            instances.remove(normalizedDir);
            start(normalizedDir, null, null);
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

    private ProcessBuilder createProcessBuilder(String projectDir, String buildTool, Integer httpPort) {
        File dir = new File(projectDir);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + projectDir);
        }

        ProcessBuilder pb;
        if ("gradle".equalsIgnoreCase(buildTool)) {
            pb = createGradleProcessBuilder(dir);
        } else {
            pb = createMavenProcessBuilder(dir);
        }

        if (httpPort != null) {
            pb.command().add("-Dquarkus.http.port=" + httpPort);
            pb.command().add("-Dquarkus.http.test-port=0");
        }

        pb.directory(dir);
        pb.redirectErrorStream(false);
        return pb;
    }

    private ProcessBuilder createMavenProcessBuilder(File projectDir) {
        String cmd;
        if (mvnCmd.isPresent()) {
            cmd = mvnCmd.get();
        } else {
            File wrapper = isWindows() ? new File(projectDir, "mvnw.cmd") : new File(projectDir, "mvnw");
            if (wrapper.exists() && verifyTrustedWrapper(wrapper)) {
                cmd = isWindows() ? "mvnw.cmd" : "./mvnw";
            } else {
                cmd = "mvn";
            }
        }
        return new ProcessBuilder(cmd, "quarkus:dev", "-Dquarkus.console.basic=true",
                "-Dquarkus.dev-mcp.enabled=true");
    }

    private ProcessBuilder createGradleProcessBuilder(File projectDir) {
        String cmd;
        if (gradleCmd.isPresent()) {
            cmd = gradleCmd.get();
        } else {
            File wrapper = isWindows() ? new File(projectDir, "gradlew.bat") : new File(projectDir, "gradlew");
            if (wrapper.exists() && verifyTrustedWrapper(wrapper)) {
                cmd = isWindows() ? "gradlew.bat" : "./gradlew";
            } else {
                cmd = "gradle";
            }
        }
        return new ProcessBuilder(cmd, "quarkusDev", "-Dquarkus.console.basic=true",
                "-Dquarkus.dev-mcp.enabled=true");
    }

    /**
     * Returns true if the wrapper is tracked by git (trusted), false if verification
     * failed and the caller should fall back to the system build tool.
     * Throws if the wrapper is explicitly untracked (potential supply-chain attack).
     */
    private boolean verifyTrustedWrapper(File wrapper) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--error-unmatch", wrapper.getName())
                    .directory(wrapper.getParentFile())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "Wrapper script '" + wrapper.getAbsolutePath() + "' is NOT tracked by git. "
                                + "It could be malicious. Add it to git or use the system-installed build tool.");
            }
            return true;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("Could not verify wrapper script '%s' against git: %s. "
                    + "Falling back to system build tool.", wrapper.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
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

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
