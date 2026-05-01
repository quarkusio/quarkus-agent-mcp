package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.logging.Logger;

public class AppLogTools {

    private static final Logger LOG = Logger.getLogger(AppLogTools.class);

    @Inject
    QuarkusProcessManager processManager;

    @Tool(name = "quarkus_app_log_enable", description = "Enable file logging for a managed Quarkus application. "
            + "Captures the application's stdout/stderr to ~/.quarkus/apps/<project>/quarkus-dev.log. "
            + "Use this when running in stdio mode to persist application logs that would otherwise only be visible in the Dev UI. "
            + "Can also be enabled permanently by setting agent-mcp.app-log.enabled=true "
            + "(e.g. via AGENT_MCP_APP_LOG_ENABLED=true environment variable).")
    ToolResponse enableAppLogging(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null) {
            return ToolResponse.error("No managed Quarkus instance found at: " + projectDir);
        }
        if (instance.getLogFile() != null) {
            return ToolResponse.success("App file logging is already enabled. Log file: " + instance.getLogFile());
        }
        Path logFile = QuarkusProcessManager.computeLogFile(projectDir);
        instance.enableFileLogging(logFile);
        return ToolResponse.success("App file logging enabled. Log file: " + logFile);
    }

    @Tool(name = "quarkus_app_log_disable", description = "Disable file logging for a managed Quarkus application. "
            + "The log file is preserved on disk for later inspection.")
    ToolResponse disableAppLogging(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null) {
            return ToolResponse.error("No managed Quarkus instance found at: " + projectDir);
        }
        Path logFile = instance.getLogFile();
        instance.disableFileLogging();
        if (logFile != null) {
            return ToolResponse.success("App file logging disabled. Log file preserved at: " + logFile);
        }
        return ToolResponse.success("App file logging is not enabled.");
    }

    @Tool(name = "quarkus_app_log", description = "Read the log file of a managed Quarkus application. "
            + "Returns the most recent lines from ~/.quarkus/apps/<project>/quarkus-dev.log. "
            + "The log file exists if app file logging was enabled via agent-mcp.app-log.enabled=true "
            + "or by calling quarkus_app_log_enable.",
            annotations = @Tool.Annotations(title = "quarkus_app_log", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse readAppLog(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Number of recent lines to return (default: 100)", required = false) Integer lines) {
        Path logFile = QuarkusProcessManager.computeLogFile(projectDir);
        if (!Files.exists(logFile)) {
            return ToolResponse.error("No app log file found at " + logFile
                    + ". Call quarkus_app_log_enable first to start logging.");
        }
        try {
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 100;
            List<String> tail = readTail(logFile, count);
            return ToolResponse.success(String.join("\n", tail));
        } catch (IOException e) {
            LOG.error("Failed to read app log file", e);
            return ToolResponse.error("Failed to read app log file: " + e.getMessage());
        }
    }

    private static List<String> readTail(Path file, int lineCount) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length == 0) {
                return List.of();
            }

            List<String> result = new ArrayList<>();
            long pos = length - 1;

            raf.seek(pos);
            if (raf.readByte() == '\n') {
                pos--;
            }

            while (pos >= 0 && result.size() < lineCount) {
                raf.seek(pos);
                if (raf.readByte() == '\n') {
                    result.add(readLineAt(raf, pos + 1));
                }
                pos--;
            }
            if (result.size() < lineCount) {
                result.add(readLineAt(raf, 0));
            }

            Collections.reverse(result);
            return result;
        }
    }

    private static String readLineAt(RandomAccessFile raf, long start) throws IOException {
        raf.seek(start);
        String line = raf.readLine();
        return line != null ? new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8) : "";
    }
}
