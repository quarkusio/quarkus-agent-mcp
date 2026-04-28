package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.FileHandler;

public class AgentLogTools {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AgentLogTools.class);

    private static final Path LOG_DIR = Path.of(System.getProperty("user.home"), ".quarkus", "agent-mcp");
    private static final Path LOG_FILE = LOG_DIR.resolve("agent-mcp.log");
    private static volatile FileHandler activeHandler;

    @Tool(name = "quarkus_agent_log_enable", description = "Enable file logging for the Quarkus Agent MCP server. "
            + "Use this when running in stdio mode to capture log output that would otherwise be invisible. "
            + "Logs are written to ~/.quarkus/agent-mcp/agent-mcp.log.")
    ToolResponse enableLogging() {
        if (activeHandler != null) {
            return ToolResponse.success("File logging is already enabled. Log file: " + LOG_FILE);
        }
        try {
            Files.createDirectories(LOG_DIR);

            FileHandler handler = new FileHandler(LOG_FILE.toString(), false);
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
    ToolResponse disableLogging() {
        FileHandler handler = activeHandler;
        if (handler == null) {
            return ToolResponse.success("File logging is not enabled.");
        }
        Logger rootLogger = Logger.getLogger("");
        rootLogger.removeHandler(handler);
        handler.close();
        activeHandler = null;

        LOG.info("File logging disabled");
        return ToolResponse.success("File logging disabled. Log file preserved at: " + LOG_FILE);
    }

    @Tool(name = "quarkus_agent_log", description = "Read the Quarkus Agent MCP server's own log file. "
            + "Returns the most recent lines from ~/.quarkus/agent-mcp/agent-mcp.log. "
            + "The log file exists if quarkus_agent_log_enable was called previously.",
            annotations = @Tool.Annotations(title = "quarkus_agent_log", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse readLog(
            @ToolArg(description = "Number of recent lines to return (default: 100)", required = false) Integer lines) {
        if (!Files.exists(LOG_FILE)) {
            return ToolResponse.error("No log file found. Call quarkus_agent_log_enable first to start logging.");
        }
        try {
            List<String> allLines = Files.readAllLines(LOG_FILE);
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 100;
            int start = Math.max(0, allLines.size() - count);
            List<String> tail = allLines.subList(start, allLines.size());
            return ToolResponse.success(String.join("\n", tail));
        } catch (IOException e) {
            LOG.error("Failed to read log file", e);
            return ToolResponse.error("Failed to read log file: " + e.getMessage());
        }
    }
}
