package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void parseFrontmatterExtractsNameAndDescription() {
        String content = """
                ---
                name: quarkus-rest
                description: "A Jakarta REST implementation"
                license: Apache-2.0
                metadata:
                  guide: https://quarkus.io/guides/rest
                ---

                ### REST Endpoints
                Use @Path and @GET for endpoints.
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("quarkus-rest", info.name());
        assertEquals("A Jakarta REST implementation", info.description());
        assertTrue(info.content().contains("### REST Endpoints"));
        assertFalse(info.content().contains("---"));
    }

    @Test
    void parseFrontmatterHandlesMissingDescription() {
        String content = """
                ---
                name: quarkus-arc
                license: Apache-2.0
                ---

                ### CDI
                Use @Inject for DI.
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("quarkus-arc", info.name());
        assertNull(info.description());
        assertTrue(info.content().contains("### CDI"));
    }

    @Test
    void parseFrontmatterHandlesNoFrontmatter() {
        String content = "### Just Markdown\nNo frontmatter here.";

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("unknown", info.name());
        assertNull(info.description());
        assertTrue(info.content().contains("### Just Markdown"));
    }

    @Test
    void readSkillsFromJarFindsSkillFiles() throws Exception {
        Path jarPath = tempDir.resolve("quarkus-extension-skills-999-SNAPSHOT.jar");
        String skillMd = """
                ---
                name: quarkus-rest
                description: "REST extension"
                license: Apache-2.0
                ---

                ### REST Endpoints
                Use @Path.
                """;

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("REST extension", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("### REST Endpoints"));
    }

    @Test
    void readSkillsFromJarFindsMultipleSkills() throws Exception {
        Path jarPath = tempDir.resolve("quarkus-extension-skills-999-SNAPSHOT.jar");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### REST
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-arc/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-arc
                    description: "CDI extension"
                    ---

                    ### CDI
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertEquals(2, skills.size());
    }

    @Test
    void readSkillsFromJarReturnsEmptyForNoSkills() throws Exception {
        Path jarPath = tempDir.resolve("some-lib.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.yaml"));
            jos.write("name: something".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertTrue(skills.isEmpty());
    }

    @Test
    void resolveSkillsJarPathConstructsCorrectPath() {
        Path result = SkillReader.resolveSkillsJarPath(
                "3.21.2",
                Path.of("/home/user/.m2/repository"));

        assertEquals(
                Path.of("/home/user/.m2/repository/io/quarkus/quarkus-extension-skills/3.21.2/quarkus-extension-skills-3.21.2.jar"),
                result);
    }

    @Test
    void downloadSkipsSnapshotVersions() {
        Path targetPath = tempDir.resolve("skills.jar");
        Path result = SkillReader.downloadFromMavenRepo("999-SNAPSHOT", targetPath, tempDir.toString());
        assertNull(result);
    }

    @Test
    void mirrorOfMatchesCentral() {
        assertTrue(SkillReader.mirrorOfMatchesCentral("central"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("*"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("external:*"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("central,jboss"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("*,!jboss"));
    }

    @Test
    void mirrorOfDoesNotMatchWhenCentralExcluded() {
        assertFalse(SkillReader.mirrorOfMatchesCentral("!central"));
        assertFalse(SkillReader.mirrorOfMatchesCentral("*,!central"));
        assertFalse(SkillReader.mirrorOfMatchesCentral("jboss"));
        assertFalse(SkillReader.mirrorOfMatchesCentral("external:http"));
    }

    @Test
    void readLocalSkillsFindsSkillFiles() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Files.createDirectories(restDir);
        Files.writeString(restDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Local REST skill"
                ---

                ### Local REST
                Local override content.
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Local REST skill", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("Local override content."));
    }

    @Test
    void readLocalSkillsFindsMultipleSkills() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Path arcDir = skillsDir.resolve("quarkus-arc");
        Files.createDirectories(restDir);
        Files.createDirectories(arcDir);
        Files.writeString(restDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                ---

                ### REST
                """);
        Files.writeString(arcDir.resolve("SKILL.md"), """
                ---
                name: quarkus-arc
                ---

                ### CDI
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(2, skills.size());
    }

    @Test
    void readLocalSkillsReturnsEmptyWhenDirDoesNotExist() {
        Path nonExistent = tempDir.resolve("no-such-dir");

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(nonExistent);

        assertTrue(skills.isEmpty());
    }

    @Test
    void readLocalSkillsIgnoresNonSkillFiles() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Files.createDirectories(restDir);
        Files.writeString(restDir.resolve("README.md"), "Not a skill file");

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertTrue(skills.isEmpty());
    }

    @Test
    void readLocalSkillsFromProjectDir() throws Exception {
        // Simulate a project with skills under .quarkus/skills/
        Path projectSkillsDir = tempDir.resolve(".quarkus/skills/quarkus-rest");
        Files.createDirectories(projectSkillsDir);
        Files.writeString(projectSkillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Project-level REST skill"
                ---

                ### Custom REST patterns for this project
                """);

        Path skillsDir = tempDir.resolve(".quarkus/skills");
        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Project-level REST skill", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("Custom REST patterns"));
    }

    @Test
    void parseMirrorUrlFromSettingsXml() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <mirrors>
                        <mirror>
                            <id>company-mirror</id>
                            <url>https://artifactory.company.com/maven-central/</url>
                            <mirrorOf>*</mirrorOf>
                        </mirror>
                    </mirrors>
                </settings>
                """);

        String url = SkillReader.parseMirrorUrl(settingsFile);

        assertEquals("https://artifactory.company.com/maven-central", url);
    }

    @Test
    void parseMirrorUrlReturnsNullWhenNoMirror() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <profiles>
                        <profile>
                            <id>default</id>
                        </profile>
                    </profiles>
                </settings>
                """);

        String url = SkillReader.parseMirrorUrl(settingsFile);

        assertNull(url);
    }

    @Test
    void parseMirrorUrlIgnoresNonCentralMirrors() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <mirrors>
                        <mirror>
                            <id>jboss-mirror</id>
                            <url>https://mirror.example.com/jboss/</url>
                            <mirrorOf>jboss-releases</mirrorOf>
                        </mirror>
                    </mirrors>
                </settings>
                """);

        String url = SkillReader.parseMirrorUrl(settingsFile);

        assertNull(url);
    }
}
