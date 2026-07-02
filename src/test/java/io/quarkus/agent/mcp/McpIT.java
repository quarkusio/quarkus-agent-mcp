package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStdioTestClient;

public class McpIT {

    private static final List<String> EXPECTED_TOOLS = List.of(
            "quarkus_create",
            "quarkus_start",
            "quarkus_stop",
            "quarkus_restart",
            "quarkus_browser",
            "quarkus_status",
            "quarkus_logs",
            "quarkus_list",
            "quarkus_app_log",
            "quarkus_agent_log",
            "quarkus_searchDocs",
            "quarkus_searchTools",
            "quarkus_skills",
            "quarkus_updateSkill",
            "quarkus_saveSkill",
            "quarkus_callTool");

    @Test
    public void toolsList() {
        try (McpStdioTestClient client = McpAssured.newConnectedStdioClient()) {
            client.when()
                    .toolsList(page -> {
                        assertEquals(EXPECTED_TOOLS.size(), page.size());
                        for (String toolName : EXPECTED_TOOLS) {
                            var tool = page.findByName(toolName);
                            assertNotNull(tool, "Tool not found: " + toolName);
                            assertNotNull(tool.description(), "Missing description for: " + toolName);
                            assertNotNull(tool.inputSchema(), "Missing inputSchema for: " + toolName);
                        }
                    })
                    .thenAssertResults();
        }
    }

    @Test
    public void listInstances() {
        try (McpStdioTestClient client = McpAssured.newConnectedStdioClient()) {
            client.when()
                    .toolsCall("quarkus_list", response -> {
                        assertFalse(response.isError());
                        assertEquals("No managed Quarkus instances",
                                response.firstContent().asText().text());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    public void statusNotStarted() {
        try (McpStdioTestClient client = McpAssured.newConnectedStdioClient()) {
            client.when()
                    .toolsCall("quarkus_status", Map.of("projectDir", "/tmp/nonexistent"), response -> {
                        assertFalse(response.isError());
                        assertEquals("not_started", response.firstContent().asText().text());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    public void agentLogReadNoFile() {
        try (McpStdioTestClient client = McpAssured.newConnectedStdioClient()) {
            client.when()
                    .toolsCall("quarkus_agent_log", Map.of("action", "read"), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("No log file found"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    public void agentLogUnknownAction() {
        try (McpStdioTestClient client = McpAssured.newConnectedStdioClient()) {
            client.when()
                    .toolsCall("quarkus_agent_log", Map.of("action", "foo"), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text()
                                .contains("Unknown action: 'foo'"));
                    })
                    .thenAssertResults();
        }
    }
}
