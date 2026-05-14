package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

final class ProcessUtils {

    private static final Logger LOG = Logger.getLogger(ProcessUtils.class);

    private ProcessUtils() {
    }

    static boolean isCommandAvailable(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static String resolveMavenCommand(File projectDir) {
        File wrapper = isWindows() ? new File(projectDir, "mvnw.cmd") : new File(projectDir, "mvnw");
        if (wrapper.exists() && verifyTrustedWrapper(wrapper)) {
            return isWindows() ? "mvnw.cmd" : "./mvnw";
        }
        return "mvn";
    }

    static String resolveGradleCommand(File projectDir) {
        File wrapper = isWindows() ? new File(projectDir, "gradlew.bat") : new File(projectDir, "gradlew");
        if (wrapper.exists() && verifyTrustedWrapper(wrapper)) {
            return isWindows() ? "gradlew.bat" : "./gradlew";
        }
        return "gradle";
    }

    static boolean verifyTrustedWrapper(File wrapper) {
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
