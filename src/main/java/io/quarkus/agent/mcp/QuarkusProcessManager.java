package io.quarkus.agent.mcp;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    public synchronized void start(String projectDir, String buildTool) {
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

        String detectedBuildTool = buildTool != null ? buildTool : detectBuildTool(normalizedDir);
        ProcessBuilder pb = createProcessBuilder(normalizedDir, detectedBuildTool);

        try {
            Process process = pb.start();
            QuarkusInstance instance = new QuarkusInstance(normalizedDir, process, executor);
            instances.put(normalizedDir, instance);
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
                    "No Quarkus instance found at: " + normalizedDir + ". Use quarkus/start first.");
        }

        if (!instance.isAlive()) {
            instances.remove(normalizedDir);
            start(normalizedDir, null);
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

    private ProcessBuilder createProcessBuilder(String projectDir, String buildTool) {
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

        pb.directory(dir);
        pb.redirectErrorStream(false);
        return pb;
    }

    private ProcessBuilder createMavenProcessBuilder(File projectDir) {
        String mvnCmd;
        File wrapper = isWindows() ? new File(projectDir, "mvnw.cmd") : new File(projectDir, "mvnw");
        if (wrapper.exists()) {
            warnIfUntrustedWrapper(wrapper);
            mvnCmd = isWindows() ? "mvnw.cmd" : "./mvnw";
        } else {
            mvnCmd = "mvn";
        }
        return new ProcessBuilder(mvnCmd, "quarkus:dev", "-Dquarkus.console.basic=true");
    }

    private ProcessBuilder createGradleProcessBuilder(File projectDir) {
        String gradleCmd;
        File wrapper = isWindows() ? new File(projectDir, "gradlew.bat") : new File(projectDir, "gradlew");
        if (wrapper.exists()) {
            warnIfUntrustedWrapper(wrapper);
            gradleCmd = isWindows() ? "gradlew.bat" : "./gradlew";
        } else {
            gradleCmd = "gradle";
        }
        return new ProcessBuilder(gradleCmd, "quarkusDev", "-Dquarkus.console.basic=true");
    }

    /**
     * Warn if the wrapper script is not tracked by git (could be malicious).
     * A wrapper in a git repo that's been committed is considered trusted.
     */
    private void warnIfUntrustedWrapper(File wrapper) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--error-unmatch", wrapper.getName())
                    .directory(wrapper.getParentFile())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exit = p.waitFor();
            if (exit != 0) {
                LOG.warnf("WARNING: Wrapper script '%s' is NOT tracked by git. "
                        + "It could be malicious. Verify before proceeding.", wrapper.getAbsolutePath());
            }
        } catch (Exception e) {
            LOG.warnf("WARNING: Could not verify wrapper script '%s' against git. "
                    + "Ensure it is trusted before proceeding.", wrapper.getAbsolutePath());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
