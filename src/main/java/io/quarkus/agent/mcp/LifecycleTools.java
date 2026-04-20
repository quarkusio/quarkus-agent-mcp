package io.quarkus.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * MCP tools for managing Quarkus application lifecycle.
 * These tools allow AI coding agents to start, stop, restart,
 * and monitor Quarkus applications running in dev mode.
 */
public class LifecycleTools {

    private static final Logger LOG = Logger.getLogger(LifecycleTools.class);

    @Inject
    QuarkusProcessManager processManager;

    @Inject
    ObjectMapper mapper;

    @Tool(name = "quarkus_start", description = "Start a Quarkus application in dev mode. "
            + "Auto-detects Maven or Gradle. Hot reload is triggered when the app is accessed. "
            + "RULES: Always write tests. Always keep README.md updated after changes.")
    ToolResponse start(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (auto-detected if omitted)", required = false) String buildTool) {
        try {
            processManager.start(projectDir, buildTool);
            return ToolResponse.success("Quarkus application starting in dev mode at: " + projectDir);
        } catch (Exception e) {
            LOG.error("Failed to start Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_stop", description = "Stop a running Quarkus application. "
            + "Sends a graceful shutdown signal, then force-kills if needed.")
    ToolResponse stop(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.stop(projectDir);
            return ToolResponse.success("Quarkus application stopped at: " + projectDir);
        } catch (Exception e) {
            LOG.error("Failed to stop Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_restart", description = "Force restart a Quarkus application. "
            + "Only use if the app is unresponsive. Normally hot reload handles changes automatically.")
    ToolResponse restart(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.restart(projectDir);
            return ToolResponse.success("Quarkus application restart triggered at: " + projectDir);
        } catch (Exception e) {
            LOG.error("Failed to restart Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_status", description = "Get the status of a Quarkus application. "
            + "Returns: not_started, starting, running (with port), crashed, or stopped.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_status", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse status(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance == null) {
                return ToolResponse.success("not_started");
            }
            String status = instance.getStatus().name().toLowerCase();
            if (instance.getStatus() == QuarkusInstance.Status.RUNNING && instance.getHttpPort() > 0) {
                return ToolResponse.success(status + " (port: " + instance.getHttpPort() + ")");
            }
            return ToolResponse.success(status);
        } catch (Exception e) {
            LOG.error("Failed to get status for " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_logs", description = "Get recent log output from a managed Quarkus application. "
            + "For structured exception details (class, message, stack trace, user code location), "
            + "prefer quarkus_callTool with toolName 'devui-exceptions_getLastException' instead.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_logs", readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    ToolResponse logs(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Number of recent lines to return (default: 50)", required = false) Integer lines) {
        try {
            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance == null) {
                return ToolResponse.error("No instance found for: " + projectDir);
            }
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 50;
            return ToolResponse.success(instance.getRecentLogs(count));
        } catch (Exception e) {
            LOG.error("Failed to get logs for " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_list", description = "List all managed Quarkus application instances and their current status.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_list", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse list() {
        try {
            Map<String, String> instances = processManager.listInstances();
            if (instances.isEmpty()) {
                return ToolResponse.success("No managed Quarkus instances");
            }
            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instances));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize instance list: " + e.getMessage());
        }
    }
}
