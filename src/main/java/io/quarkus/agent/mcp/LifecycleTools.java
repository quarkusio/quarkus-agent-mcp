package io.quarkus.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Inject
    DevMcpProxyTools devMcpProxyTools;

    static final long STARTUP_TIMEOUT_MS = 120_000;
    static final long STARTUP_POLL_INTERVAL_MS = 2_000;

    @Tool(name = "quarkus_start", description = "Start a Quarkus application. "
            + "Blocks until the app is ready or fails. "
            + "Auto-detects Maven or Gradle. "
            + "RULES: Always write tests. Always keep README.md updated after changes.")
    ToolResponse start(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (auto-detected if omitted)", required = false) String buildTool,
            @ToolArg(description = "HTTP port for the Quarkus application (e.g. 8081). "
                    + "If omitted, defaults to 8080. When 8080 is already in use, "
                    + "an available port is assigned automatically.", required = false) Integer httpPort,
            @ToolArg(description = "Comma-separated Maven profile(s) to activate (e.g. 'myprofile' or 'p1,p2'). "
                    + "Ignored for Gradle builds.", required = false) String mavenProfiles,
            @ToolArg(description = "Space-separated extra arguments appended to the Maven/Gradle command line "
                    + "(e.g. '-Ddebug=5005 -Dfoo=bar'). Use '-Ddebug=PORT' to enable the debugger on a specific port. "
                    + "Each token is split on whitespace, so individual values must not contain spaces.",
                    required = false) String extraArgs) {
        try {
            Integer effectivePort = processManager.start(projectDir, buildTool, httpPort, mavenProfiles, extraArgs);
            String containerWarning = ContainerRuntimeChecker.containerWarning(projectDir);

            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance != null && instance.getStatus() == QuarkusInstance.Status.STARTING) {
                long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
                while (instance.getStatus() == QuarkusInstance.Status.STARTING
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(STARTUP_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            List<String> createdFiles = ProjectFiles.ensureAgentFiles(projectDir, processManager.isDevMode());
            String agentFilesNote = createdFiles.isEmpty() ? ""
                    : "\nGenerated missing agent files: " + String.join(", ", createdFiles);

            if (instance != null && instance.getStatus() == QuarkusInstance.Status.RUNNING) {
                int port = instance.getHttpPort();
                return ToolResponse.success("Quarkus application running at: " + projectDir
                        + " (port: " + port + ")" + containerWarning + agentFilesNote);
            } else if (instance != null && instance.getStatus() == QuarkusInstance.Status.CRASHED) {
                String recentLogs = instance.getRecentLogs(30);
                return ToolResponse.error("Quarkus application failed to start at: " + projectDir
                        + "\n\nRecent logs:\n" + recentLogs + containerWarning + agentFilesNote);
            } else {
                String message = "Quarkus application starting at: " + projectDir;
                if (effectivePort != null) {
                    message += " (port: " + effectivePort + ")";
                }
                message += " — still starting after timeout, use quarkus_status to check" + containerWarning
                        + agentFilesNote;
                return ToolResponse.success(message);
            }
        } catch (Exception e) {
            LOG.error("Failed to start Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_attach", description = "Attach to a Quarkus application already running in dev mode "
            + "(e.g. started manually in a terminal), so quarkus_searchTools and quarkus_callTool can proxy "
            + "to its Dev MCP server. That server is contacted first and attaching fails if it does not "
            + "answer, which is the only way to know: an app started by hand exposes Dev MCP only if it was "
            + "enabled for it, and that can come from a user-level file this server cannot see. "
            + "If httpPort is omitted, the port is read from src/main/resources/application.properties "
            + "(%dev.quarkus.http.port, then quarkus.http.port), falling back to 8080; pass it explicitly "
            + "when the application listens elsewhere. "
            + "Note that this server does not own the process, so quarkus_stop only detaches and "
            + "quarkus_restart and quarkus_logs do not work. Use quarkus_callTool instead: "
            + "devui-logstream_forceRestart to restart, devui-logstream_logHistory for logs, "
            + "devui-exceptions_getLastException for errors.")
    ToolResponse attach(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "HTTP port the application is listening on. "
                    + "If omitted, read from application.properties (defaults to 8080).",
                    required = false) Integer httpPort) {
        try {
            if (httpPort != null) {
                QuarkusProcessManager.validatePort(httpPort);
            }
            String normalizedDir = processManager.validateAttachable(projectDir);
            int port = httpPort != null ? httpPort : QuarkusProcessManager.detectHttpPort(normalizedDir);

            Optional<String> unreachable = devMcpProxyTools.probeDevMcp(port, QuarkusInstance.DEFAULT_DEV_MCP_PATH);
            if (unreachable.isPresent()) {
                return ToolResponse.error(unreachable.get() + ".\nCheck that:\n"
                        + "- the application is running in dev mode on port " + port
                        + (httpPort == null
                                ? " (guessed from application.properties — pass httpPort if it differs)"
                                : "")
                        + "\n- Dev MCP is enabled for that app: set enabled=true in ~/.quarkus/dev-mcp.properties "
                        + "to turn it on for every project, or add quarkus.dev-mcp.enabled=true to the project's "
                        + "application.properties, or restart it with -Dquarkus.dev-mcp.enabled=true. "
                        + "Apps launched by quarkus_start always get the flag; one started by hand does not "
                        + "unless it is enabled somewhere.");
            }

            processManager.registerNormalized(normalizedDir, port);
            return ToolResponse.success("Attached to external Quarkus application at: " + projectDir
                    + " (port: " + port + ")\n"
                    + "Its Dev MCP server responded, so quarkus_searchTools and quarkus_callTool are ready.\n"
                    + "The process belongs to the terminal that started it, so quarkus_stop only detaches "
                    + "and quarkus_restart and quarkus_logs do not work. Go through quarkus_callTool instead: "
                    + "devui-logstream_forceRestart, devui-logstream_logHistory, "
                    + "devui-exceptions_getLastException.");
        } catch (Exception e) {
            LOG.error("Failed to attach to Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_stop", description = "Stop a running Quarkus application. "
            + "Sends a graceful shutdown signal, then force-kills if needed. "
            + "For an application attached with quarkus_attach this only drops the attachment — "
            + "the process keeps running, since this server did not start it.")
    ToolResponse stop(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            QuarkusInstance instance = processManager.getInstance(projectDir);
            boolean external = instance != null && instance.isExternal();
            processManager.stop(projectDir);
            if (external) {
                return ToolResponse.success("Detached from Quarkus application at: " + projectDir
                        + "\nThe application is still running — stop it in the terminal that started it.");
            }
            return ToolResponse.success("Quarkus application stopped at: " + projectDir);
        } catch (Exception e) {
            LOG.error("Failed to stop Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_restart", description = "Force restart a Quarkus application. "
            + "Blocks until the app is ready or fails.")
    ToolResponse restart(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.restart(projectDir);

            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance != null && instance.getStatus() == QuarkusInstance.Status.STARTING) {
                long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
                while (instance.getStatus() == QuarkusInstance.Status.STARTING
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(STARTUP_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (instance != null && instance.getStatus() == QuarkusInstance.Status.RUNNING) {
                int port = instance.getHttpPort();
                String portInfo = port > 0 ? " (port: " + port + ")" : "";
                return ToolResponse.success("Quarkus application restarted at: " + projectDir + portInfo);
            } else if (instance != null && instance.getStatus() == QuarkusInstance.Status.CRASHED) {
                String recentLogs = instance.getRecentLogs(30);
                return ToolResponse.error("Quarkus application failed to restart at: " + projectDir
                        + "\n\nRecent logs:\n" + recentLogs);
            } else {
                return ToolResponse.success("Quarkus application restart triggered at: " + projectDir
                        + " — still starting after timeout, use quarkus_status to check");
            }
        } catch (Exception e) {
            LOG.error("Failed to restart Quarkus application at " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_browser", description = "Open the application or Dev UI in the local browser. "
            + "Sends a keystroke to the dev process stdin. "
            + "Note: the browser opens on the local machine and is not visible to the agent in remote or headless setups.",
            annotations = @Tool.Annotations(title = "quarkus_browser", destructiveHint = false))
    ToolResponse browser(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "What to open: 'app' (default) or 'devui'", required = false) String target) {
        try {
            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance == null) {
                return ToolResponse.error("No running instance found for: " + projectDir);
            }
            String effective = target != null ? target.strip().toLowerCase() : "app";
            return switch (effective) {
                case "app", "" -> {
                    instance.sendInput('w');
                    yield ToolResponse.success("Opening application in browser for: " + projectDir);
                }
                case "devui" -> {
                    instance.sendInput('d');
                    yield ToolResponse.success("Opening Dev UI in browser for: " + projectDir);
                }
                default -> ToolResponse.error("Unknown target: '" + effective + "'. Use 'app' or 'devui'.");
            };
        } catch (Exception e) {
            LOG.error("Failed to open browser at " + projectDir, e);
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
            QuarkusInstance.Status currentStatus = instance.getStatus();
            String status = currentStatus.name().toLowerCase();
            if (currentStatus == QuarkusInstance.Status.RUNNING && instance.getHttpPort() > 0) {
                return ToolResponse.success(status + " (port: " + instance.getHttpPort() + ")");
            }
            if (currentStatus == QuarkusInstance.Status.CRASHED) {
                String recentLogs = instance.getRecentLogs(100);
                Optional<String> diagnostic = ContainerRuntimeChecker.detectContainerIssues(recentLogs);
                if (diagnostic.isPresent()) {
                    return ToolResponse.success(status + "\n\n" + diagnostic.get());
                }
            }
            return ToolResponse.success(status);
        } catch (Exception e) {
            LOG.error("Failed to get status for " + projectDir, e);
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus_logs", description = "Get recent log output from a managed Quarkus application.",
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
            if (instance.isExternal()) {
                return ToolResponse.error("This server did not start the application at: " + projectDir
                        + ", so it does not capture its output — the logs go to the terminal that started it. "
                        + "Call devui-logstream_logHistory via quarkus_callTool for recent log lines, "
                        + "or devui-exceptions_getLastException for the last error.");
            }
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 50;
            String logs = instance.getRecentLogs(count);
            Optional<String> diagnostic = ContainerRuntimeChecker.detectContainerIssues(logs);
            if (diagnostic.isPresent()) {
                logs += "\n\n---\n" + diagnostic.get();
            }
            return ToolResponse.success(logs);
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
