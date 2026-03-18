package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Represents a single managed Quarkus dev mode instance.
 * Tracks the child process, captures logs in a ring buffer,
 * and detects application state from process output.
 */
public class QuarkusInstance {

    public enum Status {
        STARTING,
        RUNNING,
        CRASHED,
        STOPPED
    }

    private static final int MAX_LOG_LINES = 500;

    private final String projectDir;
    private final Process process;
    private final LinkedList<String> logBuffer = new LinkedList<>();
    private volatile Status status = Status.STARTING;
    private volatile int httpPort = -1;

    public QuarkusInstance(String projectDir, Process process, ExecutorService executor) {
        this.projectDir = projectDir;
        this.process = process;

        executor.submit(() -> captureStream(process.getInputStream()));
        executor.submit(() -> captureStream(process.getErrorStream()));
        executor.submit(() -> monitorExit());
    }

    private void captureStream(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
                System.err.println(line);

                if (line.contains("Listening on:")) {
                    parsePort(line);
                }
                if (status == Status.STARTING && isStartedLine(line)) {
                    status = Status.RUNNING;
                }
            }
        } catch (IOException e) {
            // Stream closed — expected on process termination
        }
    }

    private void monitorExit() {
        try {
            int exitCode = process.waitFor();
            if (status != Status.STOPPED) {
                status = Status.CRASHED;
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
        int idx = line.indexOf("http://");
        if (idx < 0) {
            idx = line.indexOf("https://");
        }
        if (idx >= 0) {
            try {
                String url = line.substring(idx).trim();
                // Remove trailing non-URL characters (e.g. log suffixes)
                int spaceIdx = url.indexOf(' ');
                if (spaceIdx > 0) {
                    url = url.substring(0, spaceIdx);
                }
                int port = URI.create(url).getPort();
                if (port > 0) {
                    httpPort = port;
                }
            } catch (IllegalArgumentException e) {
                // ignore malformed URLs
            }
        }
    }

    private synchronized void appendLog(String line) {
        logBuffer.addLast(line);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst();
        }
    }

    public String getProjectDir() {
        return projectDir;
    }

    public Status getStatus() {
        if (status == Status.RUNNING && !process.isAlive()) {
            status = Status.CRASHED;
        }
        return status;
    }

    public synchronized String getRecentLogs(int lines) {
        int count = Math.min(lines, logBuffer.size());
        return logBuffer.subList(logBuffer.size() - count, logBuffer.size())
                .stream()
                .collect(Collectors.joining("\n"));
    }

    public void sendInput(char c) {
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
        if (!process.isAlive()) {
            throw new IllegalStateException(
                    "Process is not running. Use quarkus/start to start a new instance.");
        }
        status = Status.STARTING;
        httpPort = -1;
        sendInput('s');
    }

    public void stop() {
        status = Status.STOPPED;
        if (process.isAlive()) {
            try {
                sendInput('q');
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
        return process.isAlive();
    }

    public int getHttpPort() {
        return httpPort;
    }
}
