package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

public class AppLogTools {

    private static final Logger LOG = Logger.getLogger(AppLogTools.class);

    @Inject
    QuarkusProcessManager processManager;

    @Tool(name = "quarkus_app_log", description = "Manage file logging for a managed Quarkus application. "
            + "Can be enabled permanently via AGENT_MCP_APP_LOG_ENABLED=true. "
            + "Actions: 'enable' starts capturing stdout/stderr to ~/.quarkus/apps/<project>/quarkus-dev.log, "
            + "'disable' stops file logging (preserves the file), "
            + "'read' (default) returns the most recent lines from the log file.",
            annotations = @Tool.Annotations(title = "quarkus_app_log", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = true))
    ToolResponse appLog(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Action to perform: 'enable', 'disable', or 'read' (default)",
                    required = false) String action,
            @ToolArg(description = "Number of recent lines to return (default: 100)",
                    required = false) Integer lines) {
        String effectiveAction = action != null ? action.strip().toLowerCase() : "read";
        return switch (effectiveAction) {
            case "enable" -> enableAppLogging(projectDir);
            case "disable" -> disableAppLogging(projectDir);
            case "read" -> readAppLog(projectDir, lines);
            default -> ToolResponse.error("Unknown action: '" + effectiveAction
                    + "'. Use 'enable', 'disable', or 'read'.");
        };
    }

    private ToolResponse enableAppLogging(String projectDir) {
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

    private ToolResponse disableAppLogging(String projectDir) {
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

    private ToolResponse readAppLog(String projectDir, Integer lines) {
        Path logFile = QuarkusProcessManager.computeLogFile(projectDir);
        if (!Files.exists(logFile)) {
            return ToolResponse.error("No app log file found at " + logFile
                    + ". Call quarkus_app_log with action 'enable' first to start logging.");
        }
        try {
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 100;
            List<String> tail = LogFileReader.readTail(logFile, count);
            String logs = String.join("\n", tail);
            Optional<String> diagnostic = ContainerRuntimeChecker.detectContainerIssues(logs);
            if (diagnostic.isPresent()) {
                logs += "\n\n---\n" + diagnostic.get();
            }
            return ToolResponse.success(logs);
        } catch (IOException e) {
            LOG.error("Failed to read app log file", e);
            return ToolResponse.error("Failed to read app log file: " + e.getMessage());
        }
    }
}
