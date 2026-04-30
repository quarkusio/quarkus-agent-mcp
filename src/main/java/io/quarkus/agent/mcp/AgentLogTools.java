package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.FileHandler;

@ApplicationScoped
public class AgentLogTools {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AgentLogTools.class);

    private static final Path LOG_DIR = Path.of(System.getProperty("user.home"), ".quarkus", "agent-mcp");
    private static final Path LOG_FILE = LOG_DIR.resolve("agent-mcp.log");
    private static volatile FileHandler activeHandler;

    @ConfigProperty(name = "agent-mcp.log.enabled")
    Optional<Boolean> logEnabled;

    void onStart(@Observes StartupEvent event) {
        if (logEnabled.orElse(false)) {
            enableLogging();
        }
    }

    @Tool(name = "quarkus_agent_log_enable", description = "Enable file logging for the Quarkus Agent MCP server. "
            + "Use this when running in stdio mode to capture log output that would otherwise be invisible. "
            + "Logs are written to ~/.quarkus/agent-mcp/agent-mcp.log.")
    synchronized ToolResponse enableLogging() {
        if (activeHandler != null) {
            return ToolResponse.success("File logging is already enabled. Log file: " + LOG_FILE);
        }
        try {
            Files.createDirectories(LOG_DIR);

            FileHandler handler = new FileHandler(LOG_FILE.toString(), true);
            handler.setFormatter(new PatternFormatter("%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c{3.}] (%t) %s%e%n"));

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(handler);
            activeHandler = handler;

            LOG.info("File logging enabled: " + LOG_FILE);
            return ToolResponse.success("File logging enabled. Log file: " + LOG_FILE);
        } catch (IOException e) {
            LOG.error("Failed to enable file logging", e);
            return ToolResponse.error("Failed to enable file logging: " + e.getMessage());
        }
    }

    @Tool(name = "quarkus_agent_log_disable", description = "Disable file logging for the Quarkus Agent MCP server. "
            + "The log file is preserved on disk for later inspection.")
    synchronized ToolResponse disableLogging() {
        FileHandler handler = activeHandler;
        if (handler == null) {
            return ToolResponse.success("File logging is not enabled.");
        }
        Logger rootLogger = Logger.getLogger("");
        rootLogger.removeHandler(handler);
        handler.close();
        activeHandler = null;

        return ToolResponse.success("File logging disabled. Log file preserved at: " + LOG_FILE);
    }

    @Tool(name = "quarkus_agent_log", description = "Read the Quarkus Agent MCP server's own log file. "
            + "Returns the most recent lines from ~/.quarkus/agent-mcp/agent-mcp.log. "
            + "The log file exists if file logging was enabled via agent-mcp.log.enabled=true or by calling quarkus_agent_log_enable.",
            annotations = @Tool.Annotations(title = "quarkus_agent_log", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse readLog(
            @ToolArg(description = "Number of recent lines to return (default: 100)", required = false) Integer lines) {
        if (!Files.exists(LOG_FILE)) {
            return ToolResponse.error("No log file found. Call quarkus_agent_log_enable first to start logging.");
        }
        try {
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 100;
            List<String> tail = readTail(LOG_FILE, count);
            return ToolResponse.success(String.join("\n", tail));
        } catch (IOException e) {
            LOG.error("Failed to read log file", e);
            return ToolResponse.error("Failed to read log file: " + e.getMessage());
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

            // Skip trailing newline if present
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
            // First line (or only line if no newline found)
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
