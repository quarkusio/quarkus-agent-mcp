package io.quarkus.agent.mcp;

import java.util.Map;

import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tools for managing Quarkus application lifecycle.
 * These tools allow AI coding agents to start, stop, restart,
 * and monitor Quarkus applications running in dev mode.
 */
public class LifecycleTools {

    @Inject
    QuarkusProcessManager processManager;

    @Inject
    ObjectMapper mapper;

    @Tool(name = "quarkus/start", description = "Start a Quarkus application in dev mode. "
            + "Auto-detects Maven or Gradle and uses the wrapper script if available. "
            + "Once running, Quarkus dev mode provides hot reload — changes are recompiled when the "
            + "app is next accessed (e.g., via an HTTP request or when tests run). "
            + "DEVELOPMENT WORKFLOW: After starting, use quarkus/searchTools with query 'test' "
            + "to find continuous testing tools. Follow this cycle: "
            + "1) Pause continuous testing before making code changes, "
            + "2) Make and save all your code changes, "
            + "3) Resume continuous testing — this triggers hot reload and runs tests, "
            + "4) Check quarkus/logs for test results. "
            + "TIP: Use quarkus/searchDocs to look up Quarkus APIs and best practices before writing code.")
    ToolResponse start(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (auto-detected if omitted)", required = false) String buildTool) {
        try {
            processManager.start(projectDir, buildTool);
            return ToolResponse.success("Quarkus application starting in dev mode at: " + projectDir);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/stop", description = "Stop a running Quarkus application. "
            + "Sends a graceful shutdown signal, then force-kills if needed.")
    ToolResponse stop(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.stop(projectDir);
            return ToolResponse.success("Quarkus application stopped at: " + projectDir);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/restart", description = "Force restart a Quarkus application. "
            + "Sends 's' to the dev mode console for a full restart. "
            + "If the process has died, spawns a new one. "
            + "NOTE: You usually do NOT need to call this — Quarkus dev mode hot-reloads "
            + "automatically when you save file changes. Only use restart if hot reload fails "
            + "or the app is in a bad state.")
    ToolResponse restart(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.restart(projectDir);
            return ToolResponse.success("Quarkus application restart triggered at: " + projectDir);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/status", description = "Get the status of a Quarkus application. "
            + "Returns: not_started, starting, running (with port), crashed, or stopped. "
            + "When running, use quarkus/searchTools to discover available Dev MCP tools "
            + "(testing, config, endpoints, dev services, extensions, etc.). "
            + "Use quarkus/searchDocs to look up Quarkus APIs and best practices before writing code.")
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
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/logs", description = "Get recent log output from a managed Quarkus application.")
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
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/list", description = "List all managed Quarkus application instances and their current status.")
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
