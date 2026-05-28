package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void validateRepoAcceptsValidFormats() {
        SkillInstaller.validateRepo("quarkusio/skills");
        SkillInstaller.validateRepo("my-org/my-repo");
        SkillInstaller.validateRepo("user.name/repo_name");
    }

    @Test
    void validateRepoRejectsInvalidFormats() {
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.validateRepo("no-slash"));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.validateRepo("too/many/slashes"));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.validateRepo("has spaces/repo"));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.validateRepo("../traversal/attack"));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.validateRepo(""));
    }

    @Test
    void parseTreeResponseExtractsSkillPaths() throws Exception {
        String json = """
                {
                  "tree": [
                    {"path": "LICENSE", "type": "blob"},
                    {"path": "README.md", "type": "blob"},
                    {"path": "skills", "type": "tree"},
                    {"path": "skills/quarkus-update", "type": "tree"},
                    {"path": "skills/quarkus-update/SKILL.md", "type": "blob"},
                    {"path": "skills/migrate-spring-to-quarkus", "type": "tree"},
                    {"path": "skills/migrate-spring-to-quarkus/SKILL.md", "type": "blob"},
                    {"path": "skills/migrate-spring-to-quarkus/modules/build.md", "type": "blob"},
                    {"path": "skills/migrate-spring-to-quarkus/modules/code.md", "type": "blob"},
                    {"path": "skills/migrate-spring-to-quarkus/references/annotation-map.md", "type": "blob"}
                  ]
                }
                """;

        List<String> paths = SkillInstaller.parseTreeResponse(json);

        assertEquals(5, paths.size());
        assertTrue(paths.contains("skills/quarkus-update/SKILL.md"));
        assertTrue(paths.contains("skills/migrate-spring-to-quarkus/SKILL.md"));
        assertTrue(paths.contains("skills/migrate-spring-to-quarkus/modules/build.md"));
        assertTrue(paths.contains("skills/migrate-spring-to-quarkus/modules/code.md"));
        assertTrue(paths.contains("skills/migrate-spring-to-quarkus/references/annotation-map.md"));
    }

    @Test
    void parseTreeResponseExcludesNonMdFiles() throws Exception {
        String json = """
                {
                  "tree": [
                    {"path": "skills/my-skill/SKILL.md", "type": "blob"},
                    {"path": "skills/my-skill/data.json", "type": "blob"},
                    {"path": "skills/my-skill/image.png", "type": "blob"}
                  ]
                }
                """;

        List<String> paths = SkillInstaller.parseTreeResponse(json);

        assertEquals(1, paths.size());
        assertEquals("skills/my-skill/SKILL.md", paths.get(0));
    }

    @Test
    void parseTreeResponseExcludesDirectoryEntries() throws Exception {
        String json = """
                {
                  "tree": [
                    {"path": "skills/quarkus-update", "type": "tree"},
                    {"path": "skills/quarkus-update/SKILL.md", "type": "blob"}
                  ]
                }
                """;

        List<String> paths = SkillInstaller.parseTreeResponse(json);

        assertEquals(1, paths.size());
        assertEquals("skills/quarkus-update/SKILL.md", paths.get(0));
    }

    @Test
    void parseTreeResponseHandlesEmptyTree() throws Exception {
        String json = """
                {"tree": []}
                """;

        List<String> paths = SkillInstaller.parseTreeResponse(json);

        assertTrue(paths.isEmpty());
    }

    @Test
    void inlineModulesAppendsModuleContent() {
        TreeMap<String, String> modules = new TreeMap<>();
        modules.put("modules/build.md", "# Build\nBuild instructions here.");
        modules.put("modules/code.md", "# Code\nCode migration steps.");

        String result = SkillInstaller.inlineModules("Main skill content.", modules);

        assertTrue(result.startsWith("Main skill content."));
        assertTrue(result.contains("<!-- Inlined from modules/build.md -->"));
        assertTrue(result.contains("Build instructions here."));
        assertTrue(result.contains("<!-- Inlined from modules/code.md -->"));
        assertTrue(result.contains("Code migration steps."));
    }

    @Test
    void inlineModulesAppendsReferences() {
        TreeMap<String, String> modules = new TreeMap<>();
        modules.put("references/annotation-map.md", "# Annotation Map\n@Component -> @ApplicationScoped");

        String result = SkillInstaller.inlineModules("Main content.", modules);

        assertTrue(result.contains("<!-- Inlined from references/annotation-map.md -->"));
        assertTrue(result.contains("@Component -> @ApplicationScoped"));
    }

    @Test
    void inlineModulesHandlesBothModulesAndReferences() {
        TreeMap<String, String> modules = new TreeMap<>();
        modules.put("modules/build.md", "Build stuff");
        modules.put("references/deps.md", "Dependency map");

        String result = SkillInstaller.inlineModules("Main.", modules);

        assertTrue(result.contains("modules/build.md"));
        assertTrue(result.contains("references/deps.md"));
    }

    @Test
    void inlineModulesReturnsUnchangedWhenEmpty() {
        String result = SkillInstaller.inlineModules("Just the skill content.", new TreeMap<>());

        assertEquals("Just the skill content.", result);
    }

    @Test
    void inlineModulesOrdersFilesDeterministically() {
        TreeMap<String, String> modules = new TreeMap<>();
        modules.put("modules/zebra.md", "Zebra");
        modules.put("modules/alpha.md", "Alpha");

        String result = SkillInstaller.inlineModules("Main.", modules);

        int alphaPos = result.indexOf("modules/alpha.md");
        int zebraPos = result.indexOf("modules/zebra.md");
        assertTrue(alphaPos < zebraPos, "Modules should be ordered alphabetically");
    }

    @Test
    void isUserCustomizedReturnsTrueWhenModePresent() throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: "A customized skill"
                mode: enhance
                ---

                Custom content.
                """, StandardCharsets.UTF_8);

        assertTrue(SkillInstaller.isUserCustomized(skillDir.resolve("SKILL.md")));
    }

    @Test
    void isUserCustomizedReturnsFalseWhenModeWithCommunitySource() throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                mode: enhance
                source: community
                ---

                Community skill that ships with mode.
                """, StandardCharsets.UTF_8);

        assertFalse(SkillInstaller.isUserCustomized(skillDir.resolve("SKILL.md")));
    }

    @Test
    void isUserCustomizedReturnsFalseWhenNoMode() throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: "An installed skill"
                ---

                Content.
                """, StandardCharsets.UTF_8);

        assertFalse(SkillInstaller.isUserCustomized(skillDir.resolve("SKILL.md")));
    }

    @Test
    void isUserCustomizedReturnsFalseWhenFileDoesNotExist() {
        assertFalse(SkillInstaller.isUserCustomized(tempDir.resolve("nonexistent/SKILL.md")));
    }

    @Test
    void isUserCustomizedReturnsFalseWhenNoFrontmatter() throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "Just plain content, no frontmatter.", StandardCharsets.UTF_8);

        assertFalse(SkillInstaller.isUserCustomized(skillDir.resolve("SKILL.md")));
    }

    @Test
    void isUserCustomizedReturnsTrueForOverrideMode() throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                mode: override
                ---

                Overridden content.
                """, StandardCharsets.UTF_8);

        assertTrue(SkillInstaller.isUserCustomized(skillDir.resolve("SKILL.md")));
    }

    @Test
    void extractFrontmatterReturnsFrontmatter() {
        String content = """
                ---
                name: test
                description: "A test"
                ---

                Body content.
                """;

        String frontmatter = SkillInstaller.extractFrontmatter(content);

        assertNotNull(frontmatter);
        assertTrue(frontmatter.contains("name: test"));
        assertTrue(frontmatter.contains("description:"));
    }

    @Test
    void extractFrontmatterReturnsNullWhenMissing() {
        assertNull(SkillInstaller.extractFrontmatter("No frontmatter here."));
    }

    @Test
    void extractFrontmatterReturnsNullWhenNoClosingDelimiter() {
        String content = """
                ---
                name: test
                This never closes.
                """;

        assertNull(SkillInstaller.extractFrontmatter(content));
    }

    @Test
    void addSourceMarkerInsertsIntExistingFrontmatter() {
        String content = """
                ---
                name: my-skill
                description: "A skill"
                ---

                Body content.
                """;

        String result = SkillInstaller.addSourceMarker(content);

        assertTrue(result.contains("source: community"));
        assertTrue(result.contains("name: my-skill"));
        assertTrue(result.contains("Body content."));
    }

    @Test
    void addSourceMarkerWrapsContentWithoutFrontmatter() {
        String content = "Just plain content.";

        String result = SkillInstaller.addSourceMarker(content);

        assertTrue(result.startsWith("---\nsource: community\n---"));
        assertTrue(result.contains("Just plain content."));
    }

    @Test
    void addSourceMarkerPreservesExistingSourceField() {
        String content = """
                ---
                name: my-skill
                source: custom
                ---

                Body.
                """;

        String result = SkillInstaller.addSourceMarker(content);

        assertTrue(result.contains("source: custom"));
        assertFalse(result.contains("source: community"));
    }
}
