package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Tool(name = "quarkus_agent_log", description = "Read the Quarkus Agent MCP server's own log file. "
            + "In stdio mode, server logs are not visible because stdout/stderr are used by the protocol. "
            + "File logging can be enabled permanently via AGENT_MCP_LOG_ENABLED=true. "
            + "Actions: 'enable' starts file logging to ~/.quarkus/agent-mcp/agent-mcp.log, "
            + "'disable' stops file logging (preserves the file), "
            + "'read' (default) returns the most recent lines from the log file.",
            annotations = @Tool.Annotations(title = "quarkus_agent_log", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = true))
    ToolResponse agentLog(
            @ToolArg(description = "Action to perform: 'enable', 'disable', or 'read' (default)",
                    required = false) String action,
            @ToolArg(description = "Number of recent lines to return (default: 100)",
                    required = false) Integer lines) {
        String effectiveAction = action != null ? action.strip().toLowerCase() : "read";
        return switch (effectiveAction) {
            case "enable" -> enableLogging();
            case "disable" -> disableLogging();
            case "read" -> readLog(lines);
            default -> ToolResponse.error("Unknown action: '" + effectiveAction
                    + "'. Use 'enable', 'disable', or 'read'.");
        };
    }

    private synchronized ToolResponse enableLogging() {
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

    private synchronized ToolResponse disableLogging() {
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

    private ToolResponse readLog(Integer lines) {
        if (!Files.exists(LOG_FILE)) {
            return ToolResponse.error(
                    "No log file found. Call quarkus_agent_log with action 'enable' first to start logging.");
        }
        try {
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 100;
            List<String> tail = LogFileReader.readTail(LOG_FILE, count);
            return ToolResponse.success(String.join("\n", tail));
        } catch (IOException e) {
            LOG.error("Failed to read log file", e);
            return ToolResponse.error("Failed to read log file: " + e.getMessage());
        }
    }
}
