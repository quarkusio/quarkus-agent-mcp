package io.quarkus.agent.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tools that proxy requests to a running Quarkus application's Dev MCP server.
 * Agents use searchTools to discover available tools, then callTool to invoke them.
 */
public class DevMcpProxyTools {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Inject
    QuarkusProcessManager processManager;

    @Inject
    ObjectMapper mapper;

    @Tool(name = "quarkus/searchTools", description = "Search available tools on a running Quarkus application's Dev MCP server. "
            + "Use this to discover what actions you can perform on the running app. "
            + "Available tools typically include: continuous testing (start/stop/pause/resume), "
            + "configuration management, extension add/remove, log level control, "
            + "dev services info, workspace file operations, and endpoint listing. "
            + "IMPORTANT: Always use this tool to discover available Dev MCP tools before "
            + "interacting with the running app. This includes when you need to: "
            + "run or manage tests, check or change configuration, add/remove extensions, "
            + "list endpoints (to know what URLs to test), view dev services (database URLs, etc.), "
            + "or perform any other action on the running app. "
            + "Call this tool first to discover the tool name and parameters, "
            + "then use quarkus/callTool to invoke it. "
            + "DEVELOPMENT WORKFLOW for continuous testing: "
            + "1) Search for 'test' tools to find pause/resume/start testing tools, "
            + "2) PAUSE continuous testing before making code changes, "
            + "3) Make and save ALL your code changes, "
            + "4) RESUME continuous testing — this triggers hot reload and runs tests against updated code, "
            + "5) Check quarkus/logs for test results. "
            + "This prevents test failures from partially-applied changes.")
    ToolResponse searchTools(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Search query to filter tools by name or description (case-insensitive). "
                    + "Examples: 'test' for testing tools, 'config' for configuration, "
                    + "'extension' for extension management. If omitted, returns all tools.", required = false) String query) {
        try {
            int port = resolvePort(projectDir);
            JsonNode tools = fetchDevMcpTools(port);
            if (tools == null || !tools.isArray()) {
                return ToolResponse.success("No tools available from Dev MCP");
            }

            List<JsonNode> matched = filterTools(tools, query);
            if (matched.isEmpty()) {
                return ToolResponse.success("No Dev MCP tools found matching: " + query);
            }

            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(matched));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize tools: " + e.getMessage());
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/callTool", description = "Call a tool on the running Quarkus application's Dev MCP server. "
            + "Use quarkus/searchTools first to discover available tool names and their required arguments, "
            + "then use this tool to invoke them. "
            + "Examples: toolName='devui-continuous-testing_start' to start continuous testing, "
            + "'devui-continuous-testing_pause' to pause before making changes, "
            + "'devui-continuous-testing_resume' to resume after changes are saved.")
    ToolResponse callTool(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "The name of the Dev MCP tool to call (as returned by quarkus/searchTools)") String toolName,
            @ToolArg(description = "Arguments to pass to the tool as a JSON string (matching the tool's inputSchema). "
                    + "Omit if the tool takes no arguments.", required = false) String toolArguments) {
        try {
            int port = resolvePort(projectDir);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            if (toolArguments != null && !toolArguments.isBlank()) {
                params.put("arguments", mapper.readValue(toolArguments, Map.class));
            } else {
                params.put("arguments", Map.of());
            }

            JsonNode response = callDevMcp(port, "tools/call", params);
            return extractToolResult(response);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    private int resolvePort(String projectDir) {
        QuarkusInstance instance = processManager.getInstance(projectDir);
        if (instance == null || instance.getStatus() != QuarkusInstance.Status.RUNNING) {
            throw new IllegalStateException(
                    "Quarkus application is not running at: " + projectDir + ". Start it first with quarkus/start.");
        }
        int port = instance.getHttpPort();
        if (port < 0) {
            throw new IllegalStateException("Could not detect HTTP port for the running Quarkus application.");
        }
        return port;
    }

    private List<JsonNode> filterTools(JsonNode tools, String query) {
        List<JsonNode> matched = new ArrayList<>();
        String queryLower = (query != null && !query.isBlank()) ? query.toLowerCase() : null;
        for (JsonNode tool : tools) {
            if (queryLower == null) {
                matched.add(tool);
            } else {
                String name = tool.has("name") ? tool.get("name").asText().toLowerCase() : "";
                String desc = tool.has("description") ? tool.get("description").asText().toLowerCase() : "";
                if (name.contains(queryLower) || desc.contains(queryLower)) {
                    matched.add(tool);
                }
            }
        }
        return matched;
    }

    private ToolResponse extractToolResult(JsonNode response) throws JsonProcessingException {
        if (response != null && response.has("content") && response.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode content : response.get("content")) {
                if (content.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(content.get("text").asText());
                }
            }
            if (response.has("isError") && response.get("isError").asBoolean()) {
                return ToolResponse.error(sb.toString());
            }
            return ToolResponse.success(sb.toString());
        }
        if (response != null) {
            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));
        }
        return ToolResponse.error("No response from Dev MCP");
    }

    private JsonNode fetchDevMcpTools(int port) {
        JsonNode result = callDevMcp(port, "tools/list", Map.of());
        if (result != null && result.has("tools")) {
            return result.get("tools");
        }
        return null;
    }

    private JsonNode callDevMcp(int port, String method, Map<String, Object> params) {
        try {
            String jsonRpcRequest = mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", method,
                    "params", params));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/q/dev-mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Dev MCP returned HTTP " + response.statusCode());
            }

            JsonNode body = mapper.readTree(response.body());
            if (body.has("result")) {
                return body.get("result");
            }
            if (body.has("error")) {
                String errorMsg = body.get("error").has("message")
                        ? body.get("error").get("message").asText()
                        : body.get("error").toString();
                throw new RuntimeException("Dev MCP error: " + errorMsg);
            }
            return null;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call Dev MCP: " + e.getMessage(), e);
        }
    }
}
