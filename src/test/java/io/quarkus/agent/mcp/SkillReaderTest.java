package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
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
        SkillReader reader = new SkillReader();
        Path targetPath = tempDir.resolve("skills.jar");
        Path result = reader.downloadFromMavenRepo("999-SNAPSHOT", targetPath, tempDir.toString());
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
        // Simulate a project with skills under .agent/skills/
        Path projectSkillsDir = tempDir.resolve(".agent/skills/quarkus-rest");
        Files.createDirectories(projectSkillsDir);
        Files.writeString(projectSkillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Project-level REST skill"
                ---

                ### Custom REST patterns for this project
                """);

        Path skillsDir = tempDir.resolve(".agent/skills");
        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Project-level REST skill", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("Custom REST patterns"));
    }

    @Test
    void projectSkillReplacesBaseWithoutComposition() throws Exception {
        // Base skill simulating JAR layer
        SkillReader.SkillInfo base = new SkillReader.SkillInfo(
                "quarkus-rest", "Base REST skill", "### Base REST patterns\nUse @GET for endpoints.",
                SkillReader.SkillMode.ENHANCE, List.of("web"), null);

        // Project-level skill in .agent/skills/ with ENHANCE mode
        Path projectSkillsDir = tempDir.resolve(".agent/skills/quarkus-rest");
        Files.createDirectories(projectSkillsDir);
        Files.writeString(projectSkillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Project REST conventions"
                mode: enhance
                ---

                ### Project-specific patterns
                Always use record DTOs.
                """);

        Map<String, SkillReader.SkillInfo> skillMap = new LinkedHashMap<>();
        skillMap.put(base.name(), base);

        // Project skills should replace directly — no enhance composition even though mode says enhance
        Path skillsDir = tempDir.resolve(".agent/skills");
        for (SkillReader.SkillInfo skill : SkillReader.readLocalSkills(skillsDir)) {
            skillMap.put(skill.name(), skill);
        }

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertEquals("Project REST conventions", result.description());
        assertTrue(result.content().contains("Project-specific patterns"));
        // Base content should NOT be present — project skills are standalone
        assertFalse(result.content().contains("Base REST patterns"));
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

    // --- parseMirrorInfo tests ---

    @Test
    void parseMirrorInfoReturnsBothUrlAndId() throws Exception {
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

        SkillReader.MavenRepoInfo info = SkillReader.parseMirrorInfo(settingsFile);

        assertNotNull(info);
        assertEquals("https://artifactory.company.com/maven-central", info.url());
        assertEquals("company-mirror", info.serverId());
        assertNull(info.credentials());
    }

    @Test
    void parseMirrorInfoExtractsCredentialsFromSameFile() throws Exception {
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
                    <servers>
                        <server>
                            <id>company-mirror</id>
                            <username>myuser</username>
                            <password>mypassword</password>
                        </server>
                    </servers>
                </settings>
                """);

        SkillReader.MavenRepoInfo info = SkillReader.parseMirrorInfo(settingsFile);

        assertNotNull(info);
        assertEquals("https://artifactory.company.com/maven-central", info.url());
        assertEquals("company-mirror", info.serverId());
        assertNotNull(info.credentials());
        assertEquals("myuser", info.credentials().username());
        assertEquals("mypassword", info.credentials().password());
    }

    @Test
    void parseMirrorInfoReturnsNullWhenNoMirror() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <profiles><profile><id>x</id></profile></profiles>
                </settings>
                """);

        assertNull(SkillReader.parseMirrorInfo(settingsFile));
    }

    // --- resolveMavenRepoInfo tests ---

    @Test
    void resolveMavenRepoInfoDefaultsToMavenCentral() {
        SkillReader.MavenRepoInfo info = SkillReader.resolveMavenRepoInfo("/nonexistent/project");

        assertNotNull(info);
        assertEquals("https://repo1.maven.org/maven2", info.url());
        assertNull(info.serverId());
        assertNull(info.credentials());
    }

    // --- parseServerCredentials tests ---

    @Test
    void parseServerCredentialsFindsMatchingServer() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <servers>
                        <server>
                            <id>other-repo</id>
                            <username>other</username>
                            <password>other-pass</password>
                        </server>
                        <server>
                            <id>company-mirror</id>
                            <username>myuser</username>
                            <password>mypassword</password>
                        </server>
                    </servers>
                </settings>
                """);

        SkillReader.ServerCredentials creds = SkillReader.parseServerCredentials(settingsFile, "company-mirror");

        assertNotNull(creds);
        assertEquals("myuser", creds.username());
        assertEquals("mypassword", creds.password());
    }

    @Test
    void parseServerCredentialsReturnsNullWhenNoMatch() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <servers>
                        <server>
                            <id>other-repo</id>
                            <username>other</username>
                            <password>other-pass</password>
                        </server>
                    </servers>
                </settings>
                """);

        assertNull(SkillReader.parseServerCredentials(settingsFile, "company-mirror"));
    }

    @Test
    void parseServerCredentialsReturnsNullWhenNoServersSection() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <mirrors>
                        <mirror>
                            <id>company-mirror</id>
                            <url>https://mirror.example.com/maven/</url>
                            <mirrorOf>*</mirrorOf>
                        </mirror>
                    </mirrors>
                </settings>
                """);

        assertNull(SkillReader.parseServerCredentials(settingsFile, "company-mirror"));
    }

    @Test
    void parseServerCredentialsReturnsNullWhenPasswordMissing() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <servers>
                        <server>
                            <id>company-mirror</id>
                            <username>myuser</username>
                        </server>
                    </servers>
                </settings>
                """);

        assertNull(SkillReader.parseServerCredentials(settingsFile, "company-mirror"));
    }

    @Test
    void buildAuthHeaderEncodesCredentials() {
        SkillReader.ServerCredentials creds = new SkillReader.ServerCredentials("user", "pass");
        String header = SkillReader.buildAuthHeader(creds);
        assertEquals("Basic dXNlcjpwYXNz", header);
    }

    @Test
    void buildAuthHeaderStillWorksWithEncryptedPassword() {
        SkillReader.ServerCredentials creds = new SkillReader.ServerCredentials("user", "{encryptedValue}");
        String header = SkillReader.buildAuthHeader(creds);
        assertNotNull(header);
        assertTrue(header.startsWith("Basic "));
    }

    // --- parseLocalRepository tests ---

    @Test
    void parseLocalRepositoryFromSettingsXml() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <localRepository>/custom/maven/repo</localRepository>
                </settings>
                """);

        Path result = SkillReader.parseLocalRepository(settingsFile);

        assertEquals(Path.of("/custom/maven/repo"), result);
    }

    @Test
    void parseLocalRepositoryReturnsNullWhenNotConfigured() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <profiles></profiles>
                </settings>
                """);

        Path result = SkillReader.parseLocalRepository(settingsFile);

        assertNull(result);
    }

    @Test
    void parseLocalRepositoryReturnsNullForEmptyElement() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <localRepository>   </localRepository>
                </settings>
                """);

        Path result = SkillReader.parseLocalRepository(settingsFile);

        assertNull(result);
    }

    @Test
    void parseLocalRepositoryExpandsUserHome() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <localRepository>${user.home}/custom-repo</localRepository>
                </settings>
                """);

        Path result = SkillReader.parseLocalRepository(settingsFile);

        assertEquals(Path.of(System.getProperty("user.home"), "custom-repo"), result);
    }

    @Test
    void parseLocalRepositoryHandlesMalformedXml() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, "this is not xml");

        Path result = SkillReader.parseLocalRepository(settingsFile);

        assertNull(result);
    }

    // --- resolveLocalMavenRepo tests ---

    @Test
    void resolveLocalMavenRepoDefaultsToM2Repository() {
        Path result = SkillReader.resolveLocalMavenRepo(null);

        assertEquals(Path.of(System.getProperty("user.home"), ".m2", "repository"), result);
    }

    @Test
    void resolveLocalMavenRepoReadsFromMvnConfig() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir.resolve(".mvn"));
        Files.writeString(projectDir.resolve(".mvn/maven.config"), "-s custom-settings.xml");
        Files.writeString(projectDir.resolve("custom-settings.xml"), """
                <settings>
                    <localRepository>/custom/repo</localRepository>
                </settings>
                """);

        Path result = SkillReader.resolveLocalMavenRepo(projectDir.toString());

        assertEquals(Path.of("/custom/repo"), result);
    }

    @Test
    void resolveLocalMavenRepoFallsThroughLayers() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir.resolve(".mvn"));
        Files.writeString(projectDir.resolve(".mvn/maven.config"), "-s custom-settings.xml");
        Files.writeString(projectDir.resolve("custom-settings.xml"), """
                <settings>
                    <profiles></profiles>
                </settings>
                """);

        Path result = SkillReader.resolveLocalMavenRepo(projectDir.toString());

        assertEquals(Path.of(System.getProperty("user.home"), ".m2", "repository"), result);
    }

    // --- Enhance mode tests ---

    @Test
    void parseFrontmatterDefaultsToEnhanceMode() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void parseFrontmatterParsesEnhanceMode() {
        String content = """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Extra REST patterns
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void parseFrontmatterParsesOverrideMode() {
        String content = """
                ---
                name: quarkus-rest
                mode: override
                ---

                ### Fully custom REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(SkillReader.SkillMode.OVERRIDE, info.mode());
    }

    @Test
    void enhanceModeAppendsContentToBaseSkill() throws Exception {
        // Create a JAR with a base skill
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### Base REST patterns
                    Use @Path and @GET.
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        // Create a local enhance skill
        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Project conventions
                Always use configKey with @RegisterRestClient.
                """);

        // Load and overlay
        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertTrue(result.content().contains("### Base REST patterns"), "Should contain base content");
        assertTrue(result.content().contains("### Project conventions"), "Should contain enhanced content");
        assertTrue(result.content().contains("Use @Path and @GET."), "Should preserve base details");
        assertTrue(result.content().contains("Always use configKey"), "Should include enhancement");
        assertEquals("REST extension", result.description(), "Should keep base description when enhance has none");
    }

    @Test
    void overrideModeReplacesBaseSkill() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### Base REST patterns
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Custom REST"
                mode: override
                ---

                ### Fully custom REST
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertFalse(result.content().contains("Base REST patterns"), "Should not contain base content");
        assertTrue(result.content().contains("Fully custom REST"), "Should contain override content");
        assertEquals("Custom REST", result.description());
    }

    @Test
    void enhanceModePreservesBaseDescriptionWhenNotOverridden() throws Exception {
        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Extra patterns
                """);

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        assertEquals(1, local.size());
        assertNull(local.get(0).description(), "Enhance skill without description should be null");
    }

    @Test
    void enhanceModeWithDescriptionOverridesBaseDescription() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "Base description"
                    ---

                    ### Base content
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Enhanced description"
                mode: enhance
                ---

                ### Enhanced content
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        assertEquals("Enhanced description", skillMap.get("quarkus-rest").description());
    }

    // --- writeSkill tests ---

    @Test
    void writeSkillCreatesFileWithCorrectFrontmatter() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### My custom patterns\nUse records for DTOs.",
                "Custom REST skill",
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        assertTrue(Files.exists(written));
        String content = Files.readString(written);
        assertTrue(content.contains("name: quarkus-rest"));
        assertTrue(content.contains("description: \"Custom REST skill\""));
        assertFalse(content.contains("mode:"), "Project-scoped skills should not include mode");
        assertTrue(content.contains("### My custom patterns"));
        assertEquals(projectDir.resolve(".agent/skills/quarkus-rest/SKILL.md"), written);
    }

    @Test
    void writeSkillGlobalScopeWritesToUserDir() throws Exception {
        Path globalDir = tempDir.resolve("global-skills");
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Global patterns",
                null,
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), globalDir, false);

        assertEquals(globalDir.resolve("quarkus-rest/SKILL.md"), written);
        String content = Files.readString(written);
        assertTrue(content.contains("name: quarkus-rest"));
        assertTrue(content.contains("mode: enhance"));
        assertFalse(content.contains("description:"), "Should not include description when null");
    }

    @Test
    void writeSkillWithOverrideModeGlobal() throws Exception {
        Path globalDir = tempDir.resolve("global-skills");

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Full replacement",
                "Override skill",
                null,
                SkillReader.SkillMode.OVERRIDE,
                null, globalDir, false);

        String content = Files.readString(written);
        assertTrue(content.contains("mode: override"));
    }

    @Test
    void writeSkillProjectScopeOmitsMode() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Full replacement",
                "Override skill",
                null,
                SkillReader.SkillMode.OVERRIDE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertFalse(content.contains("mode:"), "Project-scoped skills should not include mode");
    }

    @Test
    void writeSkillRejectsPathTraversal() {
        Path projectDir = tempDir.resolve("my-project");
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                "../etc", "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                "foo/bar", "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                "foo\\bar", "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                null, "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
    }

    @Test
    void writeSkillEscapesQuotesInDescription() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Patterns",
                "A \"quoted\" description",
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertTrue(content.contains("description: \"A \\\"quoted\\\" description\""));
    }

    // --- Metadata-only (lazy content) tests ---

    @Test
    void parseFrontmatterMetadataOnlyReturnsNullContent() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                mode: enhance
                ---

                ### REST Endpoints
                Use @Path and @GET for endpoints.
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content, true);

        assertEquals("quarkus-rest", info.name());
        assertEquals("REST extension", info.description());
        assertNull(info.content());
        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void readSkillsFromJarMetadataOnlyReturnsNullContent() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### REST Endpoints
                    Use @Path.
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath, true);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("REST extension", skills.get(0).description());
        assertNull(skills.get(0).content());
    }

    @Test
    void readLocalSkillsMetadataOnlyReturnsNullContent() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Files.createDirectories(restDir);
        Files.writeString(restDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Local REST skill"
                ---

                ### Local REST
                Local content.
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir, true);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Local REST skill", skills.get(0).description());
        assertNull(skills.get(0).content());
    }

    @Test
    void enhanceModeMetadataOnlyMergesDescriptionNotContent() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "Base description"
                    ---

                    ### Base content
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Enhanced description"
                mode: enhance
                ---

                ### Enhanced content
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath, true);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"), true);
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertEquals("Enhanced description", result.description());
        assertNull(result.content());
    }

    @Test
    void skillModeFromStringDefaultsToEnhance() {
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString(null));
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString("enhance"));
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString("ENHANCE"));
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString("anything-else"));
        assertEquals(SkillReader.SkillMode.OVERRIDE, SkillReader.SkillMode.fromString("override"));
        assertEquals(SkillReader.SkillMode.OVERRIDE, SkillReader.SkillMode.fromString("OVERRIDE"));
    }

    // --- Categories tests ---

    @Test
    void parseFrontmatterExtractsCategories() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                categories: "web, reactive"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("quarkus-rest", info.name());
        assertEquals(List.of("web", "reactive"), info.categories());
    }

    @Test
    void parseFrontmatterExtractsSingleCategory() {
        String content = """
                ---
                name: quarkus-rest
                category: web
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(List.of("web"), info.categories());
    }

    @Test
    void parseFrontmatterExtractsCategoriesUnquoted() {
        String content = """
                ---
                name: quarkus-rest
                categories: web
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(List.of("web"), info.categories());
    }

    @Test
    void parseFrontmatterReturnsNullCategoriesWhenMissing() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertNull(info.categories());
    }

    @Test
    void enhanceModeMergesCategories() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    categories: "web, reactive"
                    ---

                    ### Base REST
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Extra patterns
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        assertEquals(List.of("web", "reactive"), skillMap.get("quarkus-rest").categories());
    }

    @Test
    void enhanceModeOverlayCanOverrideCategories() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    categories: "web"
                    ---

                    ### Base REST
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                categories: "messaging, reactive"
                mode: enhance
                ---

                ### Extra patterns
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        assertEquals(List.of("messaging", "reactive"), skillMap.get("quarkus-rest").categories());
    }

    @Test
    void writeSkillIncludesCategories() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Patterns",
                "REST skill",
                List.of("web", "reactive"),
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertTrue(content.contains("categories: \"web, reactive\""));
    }

    @Test
    void writeSkillOmitsCategoriesWhenNull() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Patterns",
                "REST skill",
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertFalse(content.contains("categories:"));
    }

    @Test
    void parseCategoriesHandlesCommaSeparated() {
        assertEquals(List.of("web", "reactive"), SkillReader.parseCategories("web, reactive"));
        assertEquals(List.of("web"), SkillReader.parseCategories("web"));
        assertNull(SkillReader.parseCategories("  "));
    }

    @Test
    void parseCategoriesNormalizesToLowercase() {
        assertEquals(List.of("web", "reactive"), SkillReader.parseCategories("Web, Reactive"));
        assertEquals(List.of("data"), SkillReader.parseCategories("DATA"));
    }

    @Test
    void parseFrontmatterNormalizesCategoriesToLowercase() {
        String content = """
                ---
                name: quarkus-rest
                categories: "Web, Reactive"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(List.of("web", "reactive"), info.categories());
    }

    // --- Core extension scanning tests (runtime JARs with on-the-fly composition) ---

    private Path createJar(Path dir, String fileName,
            String rawSkill, String extensionYaml) throws Exception {
        Files.createDirectories(dir);
        Path jarPath = dir.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            if (rawSkill != null) {
                jos.putNextEntry(new JarEntry("META-INF/quarkus-skill.md"));
                jos.write(rawSkill.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            if (extensionYaml != null) {
                jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.yaml"));
                jos.write(extensionYaml.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    private void createCoreExtension(Path m2Repo, String artifactId, String version,
            String rawSkill, String extensionYaml) throws Exception {
        Path quarkusDir = m2Repo.resolve("io/quarkus");
        createJar(quarkusDir.resolve(artifactId + "-deployment/" + version),
                artifactId + "-deployment-" + version + ".jar", rawSkill, null);
        createJar(quarkusDir.resolve(artifactId + "/" + version),
                artifactId + "-" + version + ".jar", null, extensionYaml);
    }

    @Test
    void scanCoreExtensionSkillsComposesFromDeploymentJar() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.21.2",
                "### CDI patterns\nUse @Inject for DI.",
                "name: \"ArC\"\ndescription: \"Build time CDI\"\nmetadata:\n  guide: \"https://quarkus.io/guides/cdi\"\n  categories:\n  - \"core\"\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);

        assertEquals(1, skills.size());
        assertEquals("quarkus-arc", skills.get(0).name());
        assertEquals("Build time CDI", skills.get(0).description());
        assertEquals(List.of("core"), skills.get(0).categories());
        assertTrue(skills.get(0).content().contains("# ArC"));
        assertTrue(skills.get(0).content().contains("Use @Inject"));
        assertTrue(skills.get(0).content().contains("Build time CDI"));
        assertTrue(skills.get(0).content().contains("https://quarkus.io/guides/cdi"));
    }

    @Test
    void scanCoreExtensionSkillsIgnoresWrongVersion() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.20.0", "### CDI", "name: ArC\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);

        assertTrue(skills.isEmpty());
    }

    @Test
    void scanCoreExtensionSkillsSkipsJarsWithoutSkills() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        // Deployment JAR with no skill, runtime JAR with metadata
        Path quarkusDir = m2Repo.resolve("io/quarkus");
        createJar(quarkusDir.resolve("quarkus-vertx-deployment/3.21.2"),
                "quarkus-vertx-deployment-3.21.2.jar", null, null);
        createJar(quarkusDir.resolve("quarkus-vertx/3.21.2"),
                "quarkus-vertx-3.21.2.jar", null, "name: Vert.x\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);

        assertTrue(skills.isEmpty());
    }

    @Test
    void scanCoreExtensionSkillsFindsMultipleSkills() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.21.2", "### CDI", "name: ArC\n");
        createCoreExtension(m2Repo, "quarkus-rest", "3.21.2", "### REST", "name: REST\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);

        assertEquals(2, skills.size());
    }

    @Test
    void scanCoreExtensionSkillsMetadataOnly() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.21.2",
                "### CDI content",
                "name: ArC\ndescription: \"Build time CDI\"\nmetadata:\n  categories:\n  - \"core\"\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, true);

        assertEquals(1, skills.size());
        assertEquals("quarkus-arc", skills.get(0).name());
        assertEquals("Build time CDI", skills.get(0).description());
        assertEquals(List.of("core"), skills.get(0).categories());
        assertNull(skills.get(0).content());
    }

    @Test
    void scanCoreExtensionSkillsReturnsEmptyForMissingDir() {
        Path m2Repo = tempDir.resolve("non-existent-m2");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);

        assertTrue(skills.isEmpty());
    }

    // --- On-the-fly composition tests ---

    @Test
    void parseExtensionYamlExtractsAllFields() {
        String yaml = """
                name: "ArC"
                description: "Build time CDI dependency injection"
                metadata:
                  guide: "https://quarkus.io/guides/cdi-reference"
                  categories:
                  - "core"
                  status: "stable"
                """;

        SkillReader.ExtensionMetadata meta = SkillReader.parseExtensionYaml(yaml);

        assertEquals("ArC", meta.name);
        assertEquals("Build time CDI dependency injection", meta.description);
        assertEquals("https://quarkus.io/guides/cdi-reference", meta.guide);
        assertEquals(List.of("core"), meta.categories);
    }

    @Test
    void parseExtensionYamlHandlesMultipleCategories() {
        String yaml = """
                name: REST
                description: "Build RESTful web services"
                metadata:
                  categories:
                  - "web"
                  - "reactive"
                """;

        SkillReader.ExtensionMetadata meta = SkillReader.parseExtensionYaml(yaml);

        assertEquals(List.of("web", "reactive"), meta.categories);
    }

    @Test
    void parseExtensionYamlUnfoldsMultilineDescription() {
        String yaml = """
                ---
                name: "REST Client"
                description: "Type-safe HTTP client for consuming REST APIs using MicroProfile REST\\
                  \\ Client"
                artifact: "io.quarkus:quarkus-rest-client:3.35.2"
                ...
                """;

        SkillReader.ExtensionMetadata meta = SkillReader.parseExtensionYaml(yaml);

        assertEquals("REST Client", meta.name);
        assertEquals("Type-safe HTTP client for consuming REST APIs using MicroProfile REST Client",
                meta.description);
    }

    @Test
    void parseExtensionYamlHandlesMinimalContent() {
        SkillReader.ExtensionMetadata meta = SkillReader.parseExtensionYaml("name: Foo\n");

        assertEquals("Foo", meta.name);
        assertNull(meta.description);
        assertNull(meta.guide);
        assertNull(meta.categories);
    }

    @Test
    void composeContentIncludesMetadataHeader() {
        SkillReader.ExtensionMetadata meta = new SkillReader.ExtensionMetadata();
        meta.name = "REST";
        meta.description = "Build RESTful APIs";
        meta.guide = "https://quarkus.io/guides/rest";

        String result = SkillReader.composeContent("### Endpoints\nUse @Path.", meta, null, "quarkus-rest");

        assertTrue(result.startsWith("# REST\n"));
        assertTrue(result.contains("> Build RESTful APIs"));
        assertTrue(result.contains("> Guide: https://quarkus.io/guides/rest"));
        assertTrue(result.contains("### Endpoints"));
    }

    @Test
    void composeContentWithNullMetaReturnsRawSkill() {
        String result = SkillReader.composeContent("### Raw skill content", null, null, "quarkus-rest");

        assertEquals("### Raw skill content\n", result);
    }

    @Test
    void composeContentIncludesMcpToolsSection() {
        SkillReader.ExtensionMetadata meta = new SkillReader.ExtensionMetadata();
        meta.name = "REST";

        Map<String, SkillReader.ParameterInfo> params = new LinkedHashMap<>();
        params.put("path", new SkillReader.ParameterInfo("The resource path", true));
        List<SkillReader.McpToolInfo> tools = List.of(
                new SkillReader.McpToolInfo("listEndpoints", "List all REST endpoints", null),
                new SkillReader.McpToolInfo("testEndpoint", "Test an endpoint", params));

        String result = SkillReader.composeContent("### REST patterns", meta, tools, "quarkus-rest");

        assertTrue(result.contains("### Available Dev MCP Tools"));
        assertTrue(result.contains("quarkus-rest_listEndpoints"));
        assertTrue(result.contains("quarkus-rest_testEndpoint"));
        assertTrue(result.contains("List all REST endpoints"));
        assertTrue(result.contains("`path` (required): The resource path"));
    }

    @Test
    void formatMcpToolsSectionFormatsTable() {
        List<SkillReader.McpToolInfo> tools = List.of(
                new SkillReader.McpToolInfo("myTool", "Does something", null));

        String result = SkillReader.formatMcpToolsSection(tools, "quarkus-rest");

        assertTrue(result.contains("| `quarkus-rest_myTool` |"));
        assertTrue(result.contains("Does something"));
        assertTrue(result.contains("—")); // no params
    }

    @Test
    void composeSkillFromExtensionCombinesDeploymentAndRuntime() throws Exception {
        Path depDir = tempDir.resolve("deployment");
        Path rtDir = tempDir.resolve("runtime");
        Path deploymentJar = createJar(depDir, "quarkus-rest-deployment-3.21.2.jar",
                "### Endpoints\nUse @Path and @GET.", null);
        Path runtimeJar = createJar(rtDir, "quarkus-rest-3.21.2.jar", null,
                "name: REST\ndescription: \"Build RESTful APIs\"\nmetadata:\n  guide: \"https://quarkus.io/guides/rest\"\n  categories:\n  - \"web\"\n  - \"reactive\"\n");

        SkillReader.SkillInfo skill = SkillReader.composeSkillFromExtension(
                deploymentJar, runtimeJar, null, "quarkus-rest", false);

        assertNotNull(skill);
        assertEquals("quarkus-rest", skill.name());
        assertEquals("Build RESTful APIs", skill.description());
        assertEquals(List.of("web", "reactive"), skill.categories());
        assertTrue(skill.content().contains("# REST"));
        assertTrue(skill.content().contains("> Build RESTful APIs"));
        assertTrue(skill.content().contains("### Endpoints"));
    }

    @Test
    void composeSkillFromExtensionWithoutRuntime() throws Exception {
        Path depDir = tempDir.resolve("deployment");
        Path deploymentJar = createJar(depDir, "dep.jar", "### Custom patterns", null);

        SkillReader.SkillInfo skill = SkillReader.composeSkillFromExtension(
                deploymentJar, Path.of("/nonexistent.jar"), null, "quarkus-custom", false);

        assertNotNull(skill);
        assertEquals("quarkus-custom", skill.name());
        assertNull(skill.description());
        assertTrue(skill.content().contains("### Custom patterns"));
    }

    @Test
    void composeSkillFromExtensionReturnsNullWhenNoSkill() throws Exception {
        Path depDir = tempDir.resolve("deployment");
        Path deploymentJar = createJar(depDir, "dep.jar", null, null);

        SkillReader.SkillInfo skill = SkillReader.composeSkillFromExtension(
                deploymentJar, Path.of("/nonexistent.jar"), null, "quarkus-vertx", false);

        assertNull(skill);
    }

    // --- Non-core extension scanning tests (runtime JARs) ---

    @Test
    void scanNonCoreExtensionSkillsFindsQuarkiverseSkills() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        Files.writeString(projectDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>1.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        // Deployment JAR with raw skill
        String groupPath = "io/quarkiverse/langchain4j";
        createJar(m2Repo.resolve(groupPath + "/quarkus-langchain4j-openai-deployment/1.2.0"),
                "quarkus-langchain4j-openai-deployment-1.2.0.jar",
                "### AI patterns\nUse AI service.", null);
        // Runtime JAR with extension metadata
        createJar(m2Repo.resolve(groupPath + "/quarkus-langchain4j-openai/1.2.0"),
                "quarkus-langchain4j-openai-1.2.0.jar", null,
                "name: LangChain4j OpenAI\ndescription: \"LangChain4j OpenAI extension\"\nmetadata:\n  categories:\n  - \"ai\"\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanNonCoreExtensionSkills(
                projectDir.toString(), m2Repo, false, false);

        assertEquals(1, skills.size());
        assertEquals("quarkus-langchain4j-openai", skills.get(0).name());
        assertEquals("LangChain4j OpenAI extension", skills.get(0).description());
        assertEquals(List.of("ai"), skills.get(0).categories());
        assertTrue(skills.get(0).content().contains("### AI patterns"));
    }

    @Test
    void scanNonCoreExtensionSkillsSkipsCoreGroupId() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        Files.writeString(projectDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus</groupId>
                            <artifactId>quarkus-rest</artifactId>
                            <version>3.21.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        createCoreExtension(m2Repo, "quarkus-rest", "3.21.2", "### REST", "name: REST\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanNonCoreExtensionSkills(
                projectDir.toString(), m2Repo, false, false);

        assertTrue(skills.isEmpty());
    }

    @Test
    void scanNonCoreExtensionSkillsHandlesMissingJar() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        Files.writeString(projectDir.resolve("pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkiverse.langchain4j</groupId>
                            <artifactId>quarkus-langchain4j-openai</artifactId>
                            <version>1.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.scanNonCoreExtensionSkills(
                projectDir.toString(), m2Repo, false, false);

        assertTrue(skills.isEmpty());
    }

    @Test
    void scanNonCoreExtensionSkillsReturnsEmptyForNullProjectDir() {
        Path m2Repo = tempDir.resolve("m2-repo");

        List<SkillReader.SkillInfo> skills = SkillReader.scanNonCoreExtensionSkills(null, m2Repo, false, false);

        assertTrue(skills.isEmpty());
    }

    @Test
    void parseSettingsFromMvnConfigHandlesEqualsForm() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path mvnDir = projectDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("maven.config"), "-s=.mvn/custom-settings.xml\n");

        Path result = SkillReader.parseSettingsFromMvnConfig(projectDir);

        assertNotNull(result);
        assertEquals(projectDir.resolve(".mvn/custom-settings.xml").normalize(), result);
    }

    @Test
    void parseSettingsFromMvnConfigHandlesLongEqualsForm() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path mvnDir = projectDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("maven.config"), "--settings=/abs/path/settings.xml\n");

        Path result = SkillReader.parseSettingsFromMvnConfig(projectDir);

        assertNotNull(result);
        assertEquals(Path.of("/abs/path/settings.xml"), result);
    }

    // --- Save skill (materialize composed skill locally) tests ---

    @Test
    void saveSkillWritesComposedContentWithOverrideMode() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.21.2",
                "### CDI patterns\nUse @Inject for DI.",
                "name: \"ArC\"\ndescription: \"Build time CDI\"\nmetadata:\n  guide: \"https://quarkus.io/guides/cdi\"\n  categories:\n  - \"core\"\n");

        // Read the composed skill (layer 1)
        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);
        assertEquals(1, skills.size());
        SkillReader.SkillInfo skill = skills.get(0);

        // Write it locally with override mode
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);
        Path written = SkillReader.writeSkill(
                skill.name(), skill.content(), skill.description(), skill.categories(),
                SkillReader.SkillMode.OVERRIDE, projectDir.toString(), null, true);

        assertTrue(Files.exists(written));
        String savedContent = Files.readString(written);
        assertTrue(savedContent.contains("name: quarkus-arc"));
        assertFalse(savedContent.contains("mode:"), "Project-scoped skills should not include mode");
        assertTrue(savedContent.contains("description: \"Build time CDI\""));
        assertTrue(savedContent.contains("categories: \"core\""));
        assertTrue(savedContent.contains("### CDI patterns"));
        assertTrue(savedContent.contains("Use @Inject"));
        assertEquals(projectDir.resolve(".agent/skills/quarkus-arc/SKILL.md"), written);
    }

    @Test
    void savedSkillOverridesBaseOnNextRead() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.21.2",
                "### CDI patterns\nUse @Inject for DI.",
                "name: \"ArC\"\ndescription: \"Build time CDI\"\n");

        // Read the base skill
        List<SkillReader.SkillInfo> baseSkills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);
        SkillReader.SkillInfo base = baseSkills.get(0);

        // Save it locally
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);
        SkillReader.writeSkill(
                base.name(), base.content(), base.description(), base.categories(),
                SkillReader.SkillMode.OVERRIDE, projectDir.toString(), null, true);

        // Verify the saved skill replaces the base (project skills are standalone, no composition)
        Map<String, SkillReader.SkillInfo> skillMap = new LinkedHashMap<>();
        skillMap.put(base.name(), base);

        Path projectSkillsDir = projectDir.resolve(".agent/skills");
        for (SkillReader.SkillInfo skill : SkillReader.readLocalSkills(projectSkillsDir)) {
            skillMap.put(skill.name(), skill);
        }

        SkillReader.SkillInfo result = skillMap.get("quarkus-arc");
        assertNotNull(result);
        // Project skills don't write mode to frontmatter, so parsed mode defaults to ENHANCE
        assertEquals(SkillReader.SkillMode.ENHANCE, result.mode());
    }

    @Test
    void saveSkillCreateOnlyRefusesToOverwriteExistingFile() throws Exception {
        Path m2Repo = tempDir.resolve("m2-repo");
        createCoreExtension(m2Repo, "quarkus-arc", "3.21.2",
                "### CDI patterns\nUse @Inject for DI.",
                "name: \"ArC\"\ndescription: \"Build time CDI\"\n");

        List<SkillReader.SkillInfo> skills = SkillReader.scanCoreExtensionSkills("3.21.2", m2Repo, false);
        SkillReader.SkillInfo skill = skills.get(0);

        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        SkillReader.writeSkill(
                skill.name(), skill.content(), skill.description(), skill.categories(),
                SkillReader.SkillMode.OVERRIDE, projectDir.toString(), null, true, true);

        assertThrows(FileAlreadyExistsException.class, () -> SkillReader.writeSkill(
                skill.name(), skill.content(), skill.description(), skill.categories(),
                SkillReader.SkillMode.OVERRIDE, projectDir.toString(), null, true, true));
    }

    @Test
    void saveSkillWithoutCreateOnlyOverwritesExistingFile() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        SkillReader.writeSkill(
                "quarkus-test", "Original content", "desc", null,
                SkillReader.SkillMode.OVERRIDE, projectDir.toString(), null, true);

        SkillReader.writeSkill(
                "quarkus-test", "Updated content", "desc", null,
                SkillReader.SkillMode.OVERRIDE, projectDir.toString(), null, true);

        Path skillFile = projectDir.resolve(".agent/skills/quarkus-test/SKILL.md");
        String content = Files.readString(skillFile);
        assertTrue(content.contains("Updated content"));
        assertFalse(content.contains("Original content"));
    }

    @Test
    void parseFrontmatterExtractsUnquotedDescription() {
        String content = """
                ---
                name: my-skill
                description: Use when the user wants to migrate
                ---

                Skill body.
                """;
        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);
        assertEquals("my-skill", info.name());
        assertEquals("Use when the user wants to migrate", info.description());
    }

    @Test
    void parseFrontmatterExtractsQuotedDescription() {
        String content = """
                ---
                name: my-skill
                description: "Check if project is up-to-date"
                ---

                Skill body.
                """;
        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);
        assertEquals("Check if project is up-to-date", info.description());
    }

    @Test
    void readBundledSkillsFindsClasspathSkills() {
        List<SkillReader.SkillInfo> skills = SkillReader.readBundledSkills(false);
        assertFalse(skills.isEmpty(), "Should find bundled skills from quarkus-skills JAR on classpath");
        assertTrue(skills.stream().anyMatch(s -> s.name().contains("quarkus-update")
                || s.name().contains("migrate")),
                "Should contain known community skills");
    }

    @Test
    void readBundledSkillsMetadataOnly() {
        List<SkillReader.SkillInfo> skills = SkillReader.readBundledSkills(true);
        assertFalse(skills.isEmpty());
        for (SkillReader.SkillInfo skill : skills) {
            assertNotNull(skill.name());
            assertNull(skill.content(), "Content should be null in metadata-only mode");
        }
    }

    // --- Module loading tests ---

    @Test
    void readSkillsFromJarReadsModuleFiles() throws Exception {
        Path jarPath = tempDir.resolve("skills-with-modules.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/SKILL.md"));
            jos.write("""
                    ---
                    name: my-skill
                    description: "Skill with modules"
                    ---

                    ### Main content
                    See [modules/build.md](modules/build.md).
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/modules/build.md"));
            jos.write("# Build Module\nBuild instructions here.".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/references/dep-map.md"));
            jos.write("# Dependency Map\n| Spring | Quarkus |".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertEquals(1, skills.size());
        SkillReader.SkillInfo skill = skills.get(0);
        assertEquals("my-skill", skill.name());
        assertNotNull(skill.modules());
        assertEquals(2, skill.modules().size());
        assertTrue(skill.modules().containsKey("modules/build.md"));
        assertTrue(skill.modules().containsKey("references/dep-map.md"));
        assertTrue(skill.modules().get("modules/build.md").contains("Build instructions"));
        assertTrue(skill.modules().get("references/dep-map.md").contains("Dependency Map"));
    }

    @Test
    void readSkillsFromJarMetadataOnlySkipsModules() throws Exception {
        Path jarPath = tempDir.resolve("skills-with-modules.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/SKILL.md"));
            jos.write("""
                    ---
                    name: my-skill
                    ---

                    ### Content
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/modules/build.md"));
            jos.write("# Build Module".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath, true);

        assertEquals(1, skills.size());
        assertNull(skills.get(0).modules());
        assertNull(skills.get(0).content());
    }

    @Test
    void readSkillsFromJarSkillWithoutModulesHasNullModules() throws Exception {
        Path jarPath = tempDir.resolve("no-modules.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/simple/SKILL.md"));
            jos.write("""
                    ---
                    name: simple
                    ---

                    ### Simple skill
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertEquals(1, skills.size());
        assertNull(skills.get(0).modules());
    }

    @Test
    void readLocalSkillsReadsModuleFiles() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path skillDir = skillsDir.resolve("my-skill");
        Path modulesDir = skillDir.resolve("modules");
        Path refsDir = skillDir.resolve("references");
        Files.createDirectories(modulesDir);
        Files.createDirectories(refsDir);

        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: "Local skill with modules"
                ---

                ### Main content
                """);
        Files.writeString(modulesDir.resolve("build.md"), "# Build Module\nLocal build content.");
        Files.writeString(refsDir.resolve("config-map.md"), "# Config Map\n| Spring | Quarkus |");

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(1, skills.size());
        SkillReader.SkillInfo skill = skills.get(0);
        assertNotNull(skill.modules());
        assertEquals(2, skill.modules().size());
        assertTrue(skill.modules().containsKey("modules/build.md"));
        assertTrue(skill.modules().containsKey("references/config-map.md"));
    }

    @Test
    void readLocalSkillsMetadataOnlySkipsModules() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path skillDir = skillsDir.resolve("my-skill");
        Path modulesDir = skillDir.resolve("modules");
        Files.createDirectories(modulesDir);

        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                ---

                ### Content
                """);
        Files.writeString(modulesDir.resolve("build.md"), "# Build Module");

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir, true);

        assertEquals(1, skills.size());
        assertNull(skills.get(0).modules());
    }

    @Test
    void enhanceModeOverlayMergesModules() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/SKILL.md"));
            jos.write("""
                    ---
                    name: my-skill
                    ---

                    ### Base
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/modules/build.md"));
            jos.write("Base build content".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/my-skill/modules/code.md"));
            jos.write("Base code content".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path localDir = tempDir.resolve("local/my-skill");
        Path localModulesDir = localDir.resolve("modules");
        Files.createDirectories(localModulesDir);
        Files.writeString(localDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                mode: enhance
                ---

                ### Enhanced
                """);
        Files.writeString(localModulesDir.resolve("build.md"), "Enhanced build content");
        Files.writeString(localModulesDir.resolve("testing.md"), "New testing module");

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        Map<String, SkillReader.SkillInfo> skillMap = new LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local"));
        SkillReader.overlaySkills(skillMap, local, "local");

        SkillReader.SkillInfo result = skillMap.get("my-skill");
        assertNotNull(result.modules());
        assertEquals(3, result.modules().size());
        assertEquals("Enhanced build content", result.modules().get("modules/build.md"));
        assertEquals("Base code content", result.modules().get("modules/code.md"));
        assertEquals("New testing module", result.modules().get("modules/testing.md"));
    }

    @Test
    void readBundledSkillsLoadsModules() {
        List<SkillReader.SkillInfo> skills = SkillReader.readBundledSkills(false);
        SkillReader.SkillInfo migrationSkill = skills.stream()
                .filter(s -> s.name().contains("migrate"))
                .findFirst()
                .orElse(null);

        if (migrationSkill != null) {
            assertNotNull(migrationSkill.modules(), "Migration skill should have module files");
            assertTrue(migrationSkill.modules().containsKey("modules/build.md"),
                    "Should contain modules/build.md");
            assertTrue(migrationSkill.modules().containsKey("references/dependency-map.md"),
                    "Should contain references/dependency-map.md");
        }
    }
}
