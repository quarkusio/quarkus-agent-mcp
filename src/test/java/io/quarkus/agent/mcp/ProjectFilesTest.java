package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureAgentFiles_createsAllWhenMissing() {
        List<String> created = ProjectFiles.ensureAgentFiles(tempDir.toString());

        assertEquals(3, created.size());
        assertTrue(created.contains(ProjectFiles.AGENTS_MD));
        assertTrue(created.contains(ProjectFiles.CLAUDE_MD));
        assertTrue(created.contains(ProjectFiles.MCP_JSON));
        assertTrue(Files.exists(tempDir.resolve(ProjectFiles.AGENTS_MD)));
        assertTrue(Files.exists(tempDir.resolve(ProjectFiles.CLAUDE_MD)));
        assertTrue(Files.exists(tempDir.resolve(ProjectFiles.MCP_JSON)));
    }

    @Test
    void ensureAgentFiles_skipsExisting() throws IOException {
        String customContent = "# My custom AGENTS.md";
        Files.writeString(tempDir.resolve(ProjectFiles.AGENTS_MD), customContent);

        List<String> created = ProjectFiles.ensureAgentFiles(tempDir.toString());

        assertEquals(2, created.size());
        assertTrue(created.contains(ProjectFiles.CLAUDE_MD));
        assertTrue(created.contains(ProjectFiles.MCP_JSON));
        assertFalse(created.contains(ProjectFiles.AGENTS_MD));
        assertEquals(customContent, Files.readString(tempDir.resolve(ProjectFiles.AGENTS_MD)));
    }

    @Test
    void ensureAgentFiles_noneNeeded() throws IOException {
        Files.writeString(tempDir.resolve(ProjectFiles.AGENTS_MD), "existing");
        Files.writeString(tempDir.resolve(ProjectFiles.CLAUDE_MD), "existing");
        Files.writeString(tempDir.resolve(ProjectFiles.MCP_JSON), "existing");

        List<String> created = ProjectFiles.ensureAgentFiles(tempDir.toString());

        assertTrue(created.isEmpty());
        assertEquals("existing", Files.readString(tempDir.resolve(ProjectFiles.AGENTS_MD)));
        assertEquals("existing", Files.readString(tempDir.resolve(ProjectFiles.CLAUDE_MD)));
        assertEquals("existing", Files.readString(tempDir.resolve(ProjectFiles.MCP_JSON)));
    }

    @Test
    void generateProjectInstructions_contentCheck() {
        ProjectFiles.generateProjectInstructions(tempDir.toString());

        assertTrue(Files.exists(tempDir.resolve(ProjectFiles.AGENTS_MD)));
        assertTrue(Files.exists(tempDir.resolve(ProjectFiles.CLAUDE_MD)));

        String agentsMd = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(ProjectFiles.AGENTS_MD)));
        assertTrue(agentsMd.contains("Extension-First Rule"));
        assertTrue(agentsMd.contains("quarkus_skills"));
        assertTrue(agentsMd.contains("quarkus_searchDocs"));
        assertTrue(agentsMd.contains("Testing"));

        String claudeMd = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(ProjectFiles.CLAUDE_MD)));
        assertTrue(claudeMd.contains("AGENTS.md"));
    }

    @Test
    void generateMcpConfig_contentCheck() {
        ProjectFiles.generateMcpConfig(tempDir.toString());

        assertTrue(Files.exists(tempDir.resolve(ProjectFiles.MCP_JSON)));

        String mcpJson = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(ProjectFiles.MCP_JSON)));
        assertTrue(mcpJson.contains("jbang"));
        assertTrue(mcpJson.contains("quarkus-agent-mcp@quarkusio"));
        assertTrue(mcpJson.contains("quarkus-agent"));
    }

    @Test
    void generateProjectInstructions_doesNotOverwrite() throws IOException {
        String customContent = "# Custom instructions";
        Files.writeString(tempDir.resolve(ProjectFiles.AGENTS_MD), customContent);

        ProjectFiles.generateProjectInstructions(tempDir.toString());

        assertEquals(customContent, Files.readString(tempDir.resolve(ProjectFiles.AGENTS_MD)));
    }

    @Test
    void generateMcpConfig_doesNotOverwrite() throws IOException {
        String customContent = "{\"custom\": true}";
        Files.writeString(tempDir.resolve(ProjectFiles.MCP_JSON), customContent);

        ProjectFiles.generateMcpConfig(tempDir.toString());

        assertEquals(customContent, Files.readString(tempDir.resolve(ProjectFiles.MCP_JSON)));
    }
}
