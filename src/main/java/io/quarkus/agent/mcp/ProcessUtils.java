package io.quarkus.agent.mcp;

import io.smallrye.common.os.OS;
import io.smallrye.common.process.ProcessUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

final class ProcessUtils {

    private static final Logger LOG = Logger.getLogger(ProcessUtils.class);

    private ProcessUtils() {
    }

    static boolean isCommandAvailable(String command) {
        return ProcessUtil.pathOfCommand(Path.of(command)).isPresent();
    }

    static String resolveMavenCommand(File projectDir) {
        Optional<String> mvnCmd = ConfigProvider.getConfig().getOptionalValue("agent-mcp.process.mvn-cmd", String.class);
        return mvnCmd.orElseGet(() -> {
            boolean win = OS.WINDOWS.isCurrent();
            File wrapper = win ? new File(projectDir, "mvnw.cmd") : new File(projectDir, "mvnw");
            if (wrapper.exists()) {
                return wrapper.getAbsolutePath();
            }
            return win ? "mvn.cmd" : "mvn";
        });
    }

    static String resolveGradleCommand(File projectDir) {
        boolean win = OS.WINDOWS.isCurrent();
        File wrapper = win ? new File(projectDir, "gradlew.bat") : new File(projectDir, "gradlew");
        if (wrapper.exists()) {
            return wrapper.getAbsolutePath();
        }
        return win ? "gradle.bat" : "gradle";
    }

    static String runAndCapture(ProcessBuilder pb, long timeout, TimeUnit unit) {
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            LOG.debugf("Failed to start process: %s", e.getMessage());
            return null;
        }
        try {
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return captureOutput(process);
                } catch (IOException e) {
                    return null;
                }
            });
            if (!process.waitFor(timeout, unit)) {
                process.destroyForcibly();
                LOG.debugf("Process timed out: %s", String.join(" ", pb.command()));
                return null;
            }
            if (process.exitValue() != 0) {
                LOG.debugf("Process exited with code %d: %s", process.exitValue(), String.join(" ", pb.command()));
                return null;
            }
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.debugf("Failed to run process: %s", e.getMessage());
            return null;
        } finally {
            process.destroyForcibly();
        }
    }

    static String captureOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                LOG.debug(line);
            }
            return sb.toString().trim();
        }
    }
}
