package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesTest {

    @Test
    void ensureAgentFiles_createsAllInEmptyDir(@TempDir Path tempDir) {
        List<String> created = ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        assertEquals(List.of("AGENTS.md", "CLAUDE.md", ".mcp.json"), created);
        assertTrue(Files.exists(tempDir.resolve("AGENTS.md")));
        assertTrue(Files.exists(tempDir.resolve("CLAUDE.md")));
        assertTrue(Files.exists(tempDir.resolve(".mcp.json")));
    }

    @Test
    void ensureAgentFiles_skipsExistingFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("AGENTS.md"), "custom content");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "custom claude");
        Files.writeString(tempDir.resolve(".mcp.json"), "{}");

        List<String> created = ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        assertTrue(created.isEmpty());
        assertEquals("custom content", Files.readString(tempDir.resolve("AGENTS.md")));
        assertEquals("custom claude", Files.readString(tempDir.resolve("CLAUDE.md")));
        assertEquals("{}", Files.readString(tempDir.resolve(".mcp.json")));
    }

    @Test
    void ensureAgentFiles_createsOnlyMissing(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("AGENTS.md"), "custom content");

        List<String> created = ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        assertEquals(List.of("CLAUDE.md", ".mcp.json"), created);
        assertEquals("custom content", Files.readString(tempDir.resolve("AGENTS.md")));
        assertTrue(Files.exists(tempDir.resolve("CLAUDE.md")));
        assertTrue(Files.exists(tempDir.resolve(".mcp.json")));
    }

    @Test
    void generatedAgentsMd_containsWorkflowInstructions(@TempDir Path tempDir) throws IOException {
        ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        String content = Files.readString(tempDir.resolve("AGENTS.md"));
        assertTrue(content.contains("Extension-First Rule"));
        assertTrue(content.contains("quarkus_skills"));
        assertTrue(content.contains("quarkus_searchDocs"));
    }

    @Test
    void generatedClaudeMd_pointsToAgentsMd(@TempDir Path tempDir) throws IOException {
        ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        String content = Files.readString(tempDir.resolve("CLAUDE.md"));
        assertTrue(content.contains("AGENTS.md"));
    }

    @Test
    void generatedMcpJson_containsServerConfig(@TempDir Path tempDir) throws IOException {
        ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        String content = Files.readString(tempDir.resolve(".mcp.json"));
        assertTrue(content.contains("quarkus-agent"));
        assertTrue(content.contains("jbang"));
        assertTrue(content.contains("quarkus-agent-mcp@quarkusio"));
    }

    @Test
    void devModeAgentsMd_containsDevTools(@TempDir Path tempDir) throws IOException {
        ProjectFiles.ensureAgentFiles(tempDir.toString(), true);

        String content = Files.readString(tempDir.resolve("AGENTS.md"));
        assertTrue(content.contains("quarkus_searchTools"));
        assertTrue(content.contains("quarkus_callTool"));
        assertTrue(content.contains("devui-testing_runTests"));
        assertTrue(content.contains("devui-exceptions_getLastException"));
        assertTrue(content.contains("devui-logstream_forceRestart"));
    }

    @Test
    void nonDevModeAgentsMd_omitsDevTools(@TempDir Path tempDir) throws IOException {
        ProjectFiles.ensureAgentFiles(tempDir.toString(), false);

        String content = Files.readString(tempDir.resolve("AGENTS.md"));
        assertFalse(content.contains("quarkus_searchTools"));
        assertFalse(content.contains("quarkus_callTool"));
        assertFalse(content.contains("devui-testing_runTests"));
        assertFalse(content.contains("devui-exceptions_getLastException"));
        assertFalse(content.contains("devui-logstream_forceRestart"));
        assertTrue(content.contains("quarkus_restart"));
        assertTrue(content.contains("quarkus_logs"));
        assertTrue(content.contains("mvn test"));
    }
}
