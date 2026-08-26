package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Represents a single managed Quarkus dev mode instance.
 * Tracks the child process, captures logs in a ring buffer,
 * and detects application state from process output.
 */
public class QuarkusInstance {

    private static final Logger LOG = Logger.getLogger(QuarkusInstance.class);

    public enum Status {
        STARTING,
        RUNNING,
        CRASHED,
        STOPPED
    }

    static final int MAX_LOG_LINES = 500;

    static final String DEFAULT_DEV_MCP_PATH = "/q/dev-mcp";

    // Quarkus dev output may be wrapped in ANSI escape codes (Gradle forces color
    // on the forked dev JVM), which must be stripped before URI parsing.
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;]*m");

    private final String projectDir;
    private final String buildTool;
    private final Integer requestedHttpPort;
    private final String mavenProfiles;
    private final String extraArgs;
    private final Process process;
    private final LinkedList<String> logBuffer = new LinkedList<>();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.STARTING);
    private volatile int httpPort = -1;
    private volatile String devMcpPath;
    private volatile PrintWriter logWriter;
    private volatile Path logFile;

    public QuarkusInstance(String projectDir, String buildTool, Integer requestedHttpPort, String mavenProfiles,
            String extraArgs, Process process, ExecutorService executor) {
        this.projectDir = projectDir;
        this.buildTool = buildTool;
        this.requestedHttpPort = requestedHttpPort;
        this.mavenProfiles = mavenProfiles;
        this.extraArgs = extraArgs;
        this.process = process;

        executor.submit(() -> captureStream(process.getInputStream()));
        executor.submit(() -> captureStream(process.getErrorStream()));
        executor.submit(() -> monitorExit());
    }

    /**
     * Creates an instance for an application started outside this server (see quarkus_attach).
     * There is no child process to manage, so the lifecycle operations that drive one
     * (restart, stop, stdin) are unavailable and log capture is empty.
     */
    public QuarkusInstance(String projectDir, int httpPort) {
        this.projectDir = projectDir;
        this.buildTool = null;
        this.requestedHttpPort = httpPort;
        this.mavenProfiles = null;
        this.extraArgs = null;
        this.process = null;
        this.httpPort = httpPort;
        this.status.set(Status.RUNNING);
    }

    public boolean isExternal() {
        return process == null;
    }

    private void captureStream(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
                String clean = ANSI.matcher(line).replaceAll("");
                if (!clean.isBlank()) {
                    System.err.println(clean);
                }

                if (clean.contains("Listening on:")) {
                    parsePort(clean);
                }
                if (clean.contains("Dev MCP available at:")) {
                    parseDevMcpPath(clean);
                }
                if (status.get() == Status.STARTING && isStartedLine(clean)) {
                    status.compareAndSet(Status.STARTING, Status.RUNNING);
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                LOG.warnf("Log capture stream closed unexpectedly while process is still alive: %s", e.getMessage());
            }
        }
    }

    private void monitorExit() {
        try {
            int exitCode = process.waitFor();
            if (status.compareAndSet(Status.STARTING, Status.CRASHED)
                    || status.compareAndSet(Status.RUNNING, Status.CRASHED)) {
                appendLog("[mcp] Process exited with code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isStartedLine(String line) {
        return line.contains("Listening on:") || line.contains("installed features:");
    }

    private void parsePort(String line) {
        int port = parsePortFromLine(line);
        if (port > 0) {
            httpPort = port;
        }
    }

    private void parseDevMcpPath(String line) {
        String marker = "Dev MCP available at:";
        int idx = line.indexOf(marker);
        if (idx >= 0) {
            String path = line.substring(idx + marker.length()).trim();
            if (!path.isEmpty()) {
                devMcpPath = path;
            }
        }
    }

    private int parsePortFromLine(String line) {
        int idx = line.indexOf("http://");
        if (idx < 0) {
            idx = line.indexOf("https://");
        }
        if (idx >= 0) {
            try {
                String url = line.substring(idx).trim();
                int spaceIdx = url.indexOf(' ');
                if (spaceIdx > 0) {
                    url = url.substring(0, spaceIdx);
                }
                url = url.replaceAll("[.,;:!?)]+$", "");
                return URI.create(url).getPort();
            } catch (IllegalArgumentException e) {
                LOG.warnf("Failed to parse URL from log line: %s", line);
            }
        }
        return -1;
    }

    private synchronized void appendLog(String line) {
        logBuffer.addLast(line);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst();
        }
        PrintWriter w = logWriter;
        if (w != null) {
            w.println(line);
        }
    }

    public synchronized void enableFileLogging(Path file) {
        if (logWriter != null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            logWriter = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file.toFile(), true), StandardCharsets.UTF_8)), true);
            logFile = file;
            LOG.infof("App file logging enabled: %s", file);
        } catch (IOException e) {
            LOG.warnf("Failed to enable app file logging for %s: %s", projectDir, e.getMessage());
        }
    }

    public synchronized void disableFileLogging() {
        PrintWriter w = logWriter;
        if (w != null) {
            w.close();
            logWriter = null;
            logFile = null;
            LOG.infof("App file logging disabled for: %s", projectDir);
        }
    }

    public String getProjectDir() {
        return projectDir;
    }

    public Status getStatus() {
        reconcileStatus();
        return status.get();
    }

    private void reconcileStatus() {
        if (isExternal()) {
            if (status.get() == Status.RUNNING && !isPortListening(httpPort)) {
                status.compareAndSet(Status.RUNNING, Status.STOPPED);
            }
            return;
        }
        if (status.get() == Status.RUNNING && !process.isAlive()) {
            status.compareAndSet(Status.RUNNING, Status.CRASHED);
        }
    }

    private static boolean isPortListening(int port) {
        if (port <= 0) {
            return false;
        }
        try (Socket s = new Socket("localhost", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized String getRecentLogs(int lines) {
        int count = Math.min(lines, logBuffer.size());
        return logBuffer.subList(logBuffer.size() - count, logBuffer.size())
                .stream()
                .collect(Collectors.joining("\n"));
    }

    public void sendInput(char c) {
        if (isExternal()) {
            throw new IllegalStateException("Cannot send input to an externally-managed process.");
        }
        OutputStream os = process.getOutputStream();
        try {
            os.write(c);
            os.write('\n');
            os.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to send input to Quarkus process: " + e.getMessage(), e);
        }
    }

    public void restart() {
        if (isExternal()) {
            throw new IllegalStateException(
                    "Cannot restart an externally-managed process: this server has no stdin to send 's' to. "
                            + "Call devui-logstream_forceRestart via quarkus_callTool instead.");
        }
        if (!process.isAlive()) {
            throw new IllegalStateException(
                    "Process is not running. Use quarkus_start to start a new instance.");
        }
        status.set(Status.STARTING);
        // Keep httpPort and devMcpPath: during a live reload the JVM stays alive
        // and the port doesn't change. The log parser will overwrite these if new
        // "Listening on:" / "Dev MCP available at:" lines appear.
        sendInput('s');
    }

    public void stop() {
        if (isExternal()) {
            status.set(Status.STOPPED);
            return;
        }
        disableFileLogging();
        status.set(Status.STOPPED);
        if (process.isAlive()) {
            try {
                sendInput('q');
            } catch (RuntimeException e) {
                LOG.debugf("Could not send quit signal (stdin may be closed): %s", e.getMessage());
            }
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isAlive() {
        if (isExternal()) {
            return status.get() == Status.RUNNING;
        }
        return process.isAlive();
    }

    public int getHttpPort() {
        return httpPort;
    }

    public String getDevMcpPath() {
        String path = devMcpPath;
        return path != null ? path : DEFAULT_DEV_MCP_PATH;
    }

    public String getBuildTool() {
        return buildTool;
    }

    public Integer getRequestedHttpPort() {
        return requestedHttpPort;
    }

    public String getMavenProfiles() {
        return mavenProfiles;
    }

    public String getExtraArgs() {
        return extraArgs;
    }

    public Path getLogFile() {
        return logFile;
    }
}
