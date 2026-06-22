package io.quarkus.agent.mcp;

import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads extension skill files (SKILL.md) using a three-layer chain:
 * <ol>
 *   <li><b>Extension skills</b> — composed on-the-fly from individual extension
 *       JARs in the local Maven repository. Raw skill content is read from
 *       {@code META-INF/quarkus-skill.md} (in deployment or runtime JARs) and
 *       combined with extension metadata from {@code META-INF/quarkus-extension.yaml}
 *       (in runtime JARs). Core extensions are discovered by version-filtered
 *       scanning; non-core extensions (Quarkiverse, custom) by parsing the
 *       project's pom.xml. Falls back to the aggregated
 *       {@code quarkus-extension-skills} JAR for older Quarkus versions.</li>
 *   <li><b>User-level skills</b> — from {@code ~/.quarkus/skills/} or a configured
 *       directory (for extension developers testing globally)</li>
 *   <li><b>Project-level skills</b> — from {@code .agent/skills/}
 *       in the project directory. These are standalone files read as-is
 *       (no composition with base layers), so any agent can read them
 *       directly from the filesystem. Use {@code quarkus_saveSkill} to
 *       materialize a fully composed skill into this directory.</li>
 * </ol>
 * Layers 1 and 2 support <b>enhance</b> (append) and <b>override</b> (replace)
 * composition via the {@code mode} field in the SKILL.md frontmatter.
 * Layer 3 (project) always replaces — no composition is applied.
 */
@ApplicationScoped
public class SkillReader {

    private static final Logger LOG = Logger.getLogger(SkillReader.class);

    private static final String SKILLS_PATH_PREFIX = "META-INF/skills/";
    private static final String RAW_SKILL_PATH = "META-INF/quarkus-skill.md";
    private static final String EXTENSION_YAML_PATH = "META-INF/quarkus-extension.yaml";
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String SKILLS_ARTIFACT_ID = "quarkus-extension-skills";
    private static final String CORE_GROUP_ID = "io.quarkus";
    private static final String DEPLOYMENT_SUFFIX = "-deployment";
    private static final String DEV_SUFFIX = "-dev";
    private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";

    record MavenRepoInfo(String url, String serverId, ServerCredentials credentials) {
    }

    record ServerCredentials(String username, String password) {
        @Override
        public String toString() {
            return "ServerCredentials[username=" + username + ", password=***]";
        }
    }

    // Jandex annotation names for MCP tool discovery
    private static final DotName JSON_RPC_DESCRIPTION = DotName
            .createSimple("io.quarkus.runtime.annotations.JsonRpcDescription");
    private static final DotName DEV_MCP_ENABLE_BY_DEFAULT = DotName
            .createSimple("io.quarkus.runtime.annotations.DevMCPEnableByDefault");
    private static final DotName DEV_MCP_BUILD_TIME_TOOL = DotName
            .createSimple("io.quarkus.devui.spi.buildtime.DevMcpBuildTimeTool");
    private static final DotName DEV_MCP_BUILD_TIME_TOOLS = DotName
            .createSimple("io.quarkus.devui.spi.buildtime.DevMcpBuildTimeTools");
    private static final DotName OPTIONAL = DotName.createSimple("java.util.Optional");
    private static final Pattern VALID_SKILL_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern FRONTMATTER_NAME = Pattern.compile("^name:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_DESC = Pattern.compile(
            "^description:\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_MODE = Pattern.compile("^mode:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_CATEGORIES = Pattern.compile(
            "^categor(?:y|ies):\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);

    // Patterns for parsing quarkus-extension.yaml (simple top-level and nested fields)
    private static final Pattern YAML_NAME = Pattern.compile("^name:\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_DESCRIPTION = Pattern.compile(
            "^description:\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_GUIDE = Pattern.compile(
            "^\\s+guide:\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_CATEGORIES_BLOCK = Pattern.compile(
            "categories:\\s*\\n((?:\\s+-\\s*.+\\n?)+)", Pattern.MULTILINE);
    private static final Pattern YAML_LIST_ITEM = Pattern.compile("^\\s+-\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);

    public enum SkillMode {
        ENHANCE,
        OVERRIDE;

        static SkillMode fromString(String value) {
            if (value != null && value.trim().equalsIgnoreCase("override")) {
                return OVERRIDE;
            }
            return ENHANCE;
        }
    }

    public record SkillInfo(String name, String description, String content, SkillMode mode, List<String> categories,
            Map<String, String> modules) {
    }

    @Inject
    WebClient webClient;

    private static final Path DEFAULT_LOCAL_SKILLS_DIR = Path.of(System.getProperty("user.home"), ".quarkus", "skills");

    /**
     * Reads all available skills using the default local skills directory
     * ({@code ~/.quarkus/skills/}).
     *
     * @see #readSkills(String, Path, boolean)
     */
    public List<SkillInfo> readSkills(String projectDir) {
        return readSkills(projectDir, null, false);
    }

    /**
     * Reads all available skills for a project using the multi-layer override chain.
     *
     * @param projectDir     the absolute path to the Quarkus project
     * @param localSkillsDir optional user-level directory to scan for SKILL.md files, or null for the default
     * @return list of available skills, never null
     */
    public List<SkillInfo> readSkills(String projectDir, Path localSkillsDir) {
        return readSkills(projectDir, localSkillsDir, false, false);
    }

    /**
     * Reads available skills for a project using the multi-layer override chain:
     * <ol>
     *   <li>Extension skills — from deployment/runtime JARs in the local Maven repo</li>
     *   <li>User-level skills — from {@code ~/.quarkus/skills/} or configured directory</li>
     *   <li>Project-level skills — from {@code .agent/skills/} in the project</li>
     * </ol>
     *
     * @param projectDir     the absolute path to the Quarkus project
     * @param localSkillsDir optional user-level directory to scan for SKILL.md files, or null for the default
     * @param metadataOnly   if true, only extract frontmatter (name, description, mode) — content will be null
     * @return list of available skills, never null
     */
    public List<SkillInfo> readSkills(String projectDir, Path localSkillsDir, boolean metadataOnly) {
        return readSkills(projectDir, localSkillsDir, metadataOnly, false);
    }

    /**
     * Reads available skills for a project using the three-layer override chain.
     *
     * @param projectDir        the absolute path to the Quarkus project
     * @param localSkillsDir    optional user-level directory to scan for SKILL.md files, or null for the default
     * @param metadataOnly      if true, only extract frontmatter (name, description, mode) — content will be null
     * @param includeTransitive if true, include skills from transitive dependencies; if false, only direct dependencies
     * @return list of available skills, never null
     */
    public List<SkillInfo> readSkills(String projectDir, Path localSkillsDir, boolean metadataOnly,
            boolean includeTransitive) {
        return readSkills(projectDir, localSkillsDir, metadataOnly, includeTransitive, !metadataOnly);
    }

    /**
     * Reads available skills with independent control over content and module loading.
     *
     * @param projectDir        the absolute path to the Quarkus project
     * @param localSkillsDir    optional user-level directory to scan for SKILL.md files, or null for the default
     * @param metadataOnly      if true, only extract frontmatter (name, description, mode) — content will be null
     * @param includeTransitive if true, include skills from transitive dependencies; if false, only direct dependencies
     * @param loadModules       if true, read module/reference .md files alongside SKILL.md
     * @return list of available skills, never null
     */
    public List<SkillInfo> readSkills(String projectDir, Path localSkillsDir, boolean metadataOnly,
            boolean includeTransitive, boolean loadModules) {
        // Use a map keyed by skill name so each layer can override the previous
        Map<String, SkillInfo> skillsByName = new LinkedHashMap<>();

        // Layer 1: Scan extension runtime JARs and compose skills on-the-fly
        String version = QuarkusVersionDetector.detect(projectDir);
        if (version != null) {
            Path m2Repo = resolveLocalMavenRepo(projectDir);

            // 1a: Scan core extension runtime JARs (io.quarkus) by version
            for (SkillInfo skill : scanCoreExtensionSkills(version, m2Repo, metadataOnly)) {
                skillsByName.put(skill.name(), skill);
            }

            // 1b: Scan non-core extension runtime JARs (Quarkiverse, custom) from pom.xml
            for (SkillInfo skill : scanNonCoreExtensionSkills(projectDir, m2Repo, metadataOnly, includeTransitive)) {
                skillsByName.putIfAbsent(skill.name(), skill);
            }

            // Fallback: try aggregated JAR for older Quarkus versions without per-extension skills
            if (skillsByName.isEmpty()) {
                LOG.debugf("No skills found in extension JARs, trying aggregated JAR for version %s", version);
                Path jarPath = resolveSkillsJarPath(version, m2Repo);

                if (!Files.isRegularFile(jarPath)) {
                    LOG.infof("Skills JAR not found locally, downloading for version %s", version);
                    jarPath = downloadFromMavenRepo(version, jarPath, projectDir);
                }

                if (jarPath != null) {
                    try {
                        for (SkillInfo skill : readSkillsFromJar(jarPath, metadataOnly, loadModules)) {
                            skillsByName.put(skill.name(), skill);
                        }
                    } catch (IOException e) {
                        LOG.warnf("Failed to read skills from %s: %s", jarPath, e.getMessage());
                    }
                }
            }
        } else {
            LOG.debugf("Could not detect Quarkus version for %s", projectDir);
        }

        // Layer 1.5: Bundled community skills (from classpath JAR)
        overlaySkills(skillsByName, readBundledSkills(metadataOnly, loadModules), "bundled (classpath)");

        // Layer 2: Overlay user-level skills (~/.quarkus/skills/ or configured dir)
        Path effectiveLocalDir = localSkillsDir != null ? localSkillsDir : DEFAULT_LOCAL_SKILLS_DIR;
        overlaySkills(skillsByName, readLocalSkills(effectiveLocalDir, metadataOnly, loadModules),
                effectiveLocalDir.toString());

        // Layer 3: Project-level skills (.agent/skills/) — standalone, no composition
        if (projectDir != null) {
            Path projectSkillsDir = Path.of(projectDir, ".agent", "skills");
            for (SkillInfo skill : readLocalSkills(projectSkillsDir, metadataOnly, loadModules)) {
                skillsByName.put(skill.name(), skill);
                LOG.infof("Skill '%s' loaded from project %s", skill.name(), projectSkillsDir);
            }
        }

        LOG.infof("Found %d skills for project %s (version %s)",
                skillsByName.size(), projectDir, version);
        return new ArrayList<>(skillsByName.values());
    }

    static void overlaySkills(Map<String, SkillInfo> target, List<SkillInfo> overlay, String source) {
        for (SkillInfo skill : overlay) {
            if (target.containsKey(skill.name())) {
                if (skill.mode() == SkillMode.ENHANCE) {
                    SkillInfo base = target.get(skill.name());
                    String mergedContent = mergeContent(base.content(), skill.content());
                    String desc = skill.description() != null ? skill.description() : base.description();
                    List<String> cats = skill.categories() != null && !skill.categories().isEmpty()
                            ? skill.categories()
                            : base.categories();
                    Map<String, String> mergedModules = mergeModules(base.modules(), skill.modules());
                    target.put(skill.name(),
                            new SkillInfo(skill.name(), desc, mergedContent, SkillMode.ENHANCE, cats, mergedModules));
                    LOG.infof("Skill '%s' enhanced by %s", skill.name(), source);
                } else {
                    target.put(skill.name(), skill);
                    LOG.infof("Skill '%s' overridden by %s", skill.name(), source);
                }
            } else {
                if (skill.mode() == SkillMode.ENHANCE) {
                    LOG.warnf("Skill '%s' uses enhance mode but no base skill exists — adding as new skill from %s",
                            skill.name(), source);
                } else {
                    LOG.infof("Skill '%s' added from %s", skill.name(), source);
                }
                target.put(skill.name(), skill);
            }
        }
    }

    private static String mergeContent(String base, String overlay) {
        if (base == null && overlay == null) {
            return null;
        }
        if (base == null) {
            return overlay;
        }
        if (overlay == null) {
            return base;
        }
        return base + "\n\n---\n\n" + overlay;
    }

    private static Map<String, String> mergeModules(Map<String, String> base, Map<String, String> overlay) {
        if (base == null && overlay == null) {
            return null;
        }
        if (base == null) {
            return overlay;
        }
        if (overlay == null) {
            return base;
        }
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return merged;
    }

    /**
     * Parses the YAML frontmatter from a SKILL.md file content.
     */
    static SkillInfo parseFrontmatter(String fullContent) {
        return parseFrontmatter(fullContent, false);
    }

    /**
     * Parses the YAML frontmatter from a SKILL.md file content.
     *
     * @param metadataOnly if true, only extract name/description/mode — content will be null
     */
    static SkillInfo parseFrontmatter(String fullContent, boolean metadataOnly) {
        String name = "unknown";
        String description = null;
        String body = metadataOnly ? null : fullContent;
        SkillMode mode = SkillMode.ENHANCE;
        List<String> categories = null;

        if (fullContent.startsWith("---")) {
            int endIdx = fullContent.indexOf("---", 3);
            if (endIdx > 0) {
                String frontmatter = fullContent.substring(3, endIdx);
                if (!metadataOnly) {
                    body = fullContent.substring(endIdx + 3).trim();
                }

                Matcher nameMatcher = FRONTMATTER_NAME.matcher(frontmatter);
                if (nameMatcher.find()) {
                    name = nameMatcher.group(1).trim();
                }

                Matcher descMatcher = FRONTMATTER_DESC.matcher(frontmatter);
                if (descMatcher.find()) {
                    description = descMatcher.group(1).trim();
                }

                Matcher modeMatcher = FRONTMATTER_MODE.matcher(frontmatter);
                if (modeMatcher.find()) {
                    mode = SkillMode.fromString(modeMatcher.group(1));
                }

                Matcher catMatcher = FRONTMATTER_CATEGORIES.matcher(frontmatter);
                if (catMatcher.find()) {
                    categories = parseCategories(catMatcher.group(1).trim());
                }
            }
        }

        return new SkillInfo(name, description, body, mode, categories, null);
    }

    static List<String> parseCategories(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    /**
     * Reads all SKILL.md files from a single JAR.
     */
    static List<SkillInfo> readSkillsFromJar(Path jarPath) throws IOException {
        return readSkillsFromJar(jarPath, false);
    }

    /**
     * Reads SKILL.md files (and their module/reference files) from a single JAR.
     *
     * @param metadataOnly if true, only extract frontmatter — content and modules will be null
     */
    static List<SkillInfo> readSkillsFromJar(Path jarPath, boolean metadataOnly) throws IOException {
        return readSkillsFromJar(jarPath, metadataOnly, !metadataOnly);
    }

    /**
     * Reads SKILL.md files from a single JAR with independent control over content and module loading.
     *
     * @param metadataOnly if true, only extract frontmatter — content will be null
     * @param loadModules  if true, read module/reference .md files alongside SKILL.md
     */
    static List<SkillInfo> readSkillsFromJar(Path jarPath, boolean metadataOnly, boolean loadModules)
            throws IOException {
        Map<String, List<JarEntry>> entriesBySkill = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith(SKILLS_PATH_PREFIX) || entry.isDirectory()
                        || !entryName.endsWith(".md")) {
                    continue;
                }
                String relative = entryName.substring(SKILLS_PATH_PREFIX.length());
                int slash = relative.indexOf('/');
                if (slash < 0) {
                    continue;
                }
                String skillDirName = relative.substring(0, slash);
                entriesBySkill.computeIfAbsent(skillDirName, k -> new ArrayList<>()).add(entry);
            }

            List<SkillInfo> skills = new ArrayList<>();
            for (var group : entriesBySkill.entrySet()) {
                String skillDir = group.getKey();
                String skillMdPath = SKILLS_PATH_PREFIX + skillDir + "/" + SKILL_FILE_NAME;

                JarEntry skillEntry = null;
                for (JarEntry e : group.getValue()) {
                    if (e.getName().equals(skillMdPath)) {
                        skillEntry = e;
                        break;
                    }
                }
                if (skillEntry == null) {
                    continue;
                }

                String content;
                try (InputStream is = jar.getInputStream(skillEntry)) {
                    content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                SkillInfo baseInfo = parseFrontmatter(content, metadataOnly);

                Map<String, String> modules = null;
                if (loadModules) {
                    String prefix = SKILLS_PATH_PREFIX + skillDir + "/";
                    for (JarEntry moduleEntry : group.getValue()) {
                        if (moduleEntry.getName().equals(skillMdPath)) {
                            continue;
                        }
                        String modulePath = moduleEntry.getName().substring(prefix.length());
                        try (InputStream is = jar.getInputStream(moduleEntry)) {
                            if (modules == null) {
                                modules = new LinkedHashMap<>();
                            }
                            modules.put(modulePath, new String(is.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }

                skills.add(new SkillInfo(baseInfo.name(), baseInfo.description(),
                        baseInfo.content(), baseInfo.mode(), baseInfo.categories(), modules));
            }
            return skills;
        }
    }

    /**
     * Reads SKILL.md files from a local directory. Scans for any
     * {@code SKILL.md} files under the given directory, following the
     * same {@code <extension-name>/SKILL.md} structure used inside
     * the aggregated JAR.
     *
     * @param skillsDir the directory to scan for SKILL.md files
     * @return list of locally found skills, never null
     */
    static List<SkillInfo> readLocalSkills(Path skillsDir) {
        return readLocalSkills(skillsDir, false);
    }

    /**
     * Reads SKILL.md files (and their module/reference files) from a local directory.
     *
     * @param skillsDir    the directory to scan for SKILL.md files
     * @param metadataOnly if true, only extract frontmatter — content and modules will be null
     * @return list of locally found skills, never null
     */
    static List<SkillInfo> readLocalSkills(Path skillsDir, boolean metadataOnly) {
        return readLocalSkills(skillsDir, metadataOnly, !metadataOnly);
    }

    /**
     * Reads SKILL.md files from a local directory with independent control over content and module loading.
     *
     * @param skillsDir    the directory to scan for SKILL.md files
     * @param metadataOnly if true, only extract frontmatter — content will be null
     * @param loadModules  if true, read module/reference .md files alongside SKILL.md
     * @return list of locally found skills, never null
     */
    static List<SkillInfo> readLocalSkills(Path skillsDir, boolean metadataOnly, boolean loadModules) {
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }

        List<SkillInfo> skills = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
            for (Path skillDir : stream) {
                Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                try {
                    String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                    SkillInfo baseInfo = parseFrontmatter(content, metadataOnly);

                    Map<String, String> modules = null;
                    if (loadModules) {
                        modules = readModuleFiles(skillDir);
                    }

                    SkillInfo skill = new SkillInfo(baseInfo.name(), baseInfo.description(),
                            baseInfo.content(), baseInfo.mode(), baseInfo.categories(), modules);
                    skills.add(skill);
                    LOG.debugf("Found local skill '%s' at %s", skill.name(), skillFile);
                } catch (IOException e) {
                    LOG.debugf("Failed to read local skill %s: %s", skillFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.debugf("Failed to scan local skills directory %s: %s", skillsDir, e.getMessage());
        }
        return skills;
    }

    private static Map<String, String> readModuleFiles(Path skillDir) {
        Map<String, String> modules = new LinkedHashMap<>();
        try {
            Files.walkFileTree(skillDir, Collections.emptySet(), 5, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".md") && !fileName.equals(SKILL_FILE_NAME)) {
                        String relativePath = skillDir.relativize(file).toString()
                                .replace('\\', '/');
                        try {
                            modules.put(relativePath, Files.readString(file, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            LOG.debugf("Failed to read module %s: %s", file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debugf("Failed to scan module files in %s: %s", skillDir, e.getMessage());
        }
        return modules.isEmpty() ? null : modules;
    }

    /**
     * Reads bundled community skills from the classpath JAR (io.quarkus:quarkus-skills).
     * Scans for {@code META-INF/skills/<name>/SKILL.md} entries.
     */
    static List<SkillInfo> readBundledSkills(boolean metadataOnly) {
        return readBundledSkills(metadataOnly, !metadataOnly);
    }

    static List<SkillInfo> readBundledSkills(boolean metadataOnly, boolean loadModules) {
        List<SkillInfo> skills = new ArrayList<>();
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                    .getResources(SKILLS_PATH_PREFIX);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String protocol = url.getProtocol();
                if ("jar".equals(protocol)) {
                    String jarPath = url.getPath();
                    int separator = jarPath.indexOf("!/");
                    if (separator > 0) {
                        String filePath = jarPath.substring(0, separator);
                        if (filePath.startsWith("file:")) {
                            filePath = filePath.substring(5);
                        }
                        Path jar = Path.of(filePath);
                        if (Files.isRegularFile(jar)) {
                            skills.addAll(readSkillsFromJar(jar, metadataOnly, loadModules));
                        }
                    }
                } else if ("file".equals(protocol)) {
                    Path dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        skills.addAll(readLocalSkills(dir.getParent(), metadataOnly, loadModules));
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to scan bundled skills from classpath: %s", e.getMessage());
        }
        return skills;
    }

    // ── Per-extension runtime JAR scanning with on-the-fly composition ─────

    /**
     * Scans core Quarkus extension deployment JARs for skills.
     * For each deployment JAR containing a raw skill file, reads extension
     * metadata from the corresponding runtime JAR and discovers MCP tools
     * via Jandex scanning.
     */
    static List<SkillInfo> scanCoreExtensionSkills(String version, Path m2Repo, boolean metadataOnly) {
        Path quarkusDir = m2Repo.resolve("io/quarkus");
        if (!Files.isDirectory(quarkusDir)) {
            return List.of();
        }

        List<SkillInfo> skills = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarkusDir,
                entry -> Files.isDirectory(entry)
                        && entry.getFileName().toString().startsWith("quarkus-")
                        && entry.getFileName().toString().endsWith(DEPLOYMENT_SUFFIX))) {
            for (Path extDir : stream) {
                String deploymentArtifactId = extDir.getFileName().toString();
                String artifactId = deploymentArtifactId.substring(0,
                        deploymentArtifactId.length() - DEPLOYMENT_SUFFIX.length());
                Path deploymentJar = extDir.resolve(version)
                        .resolve(deploymentArtifactId + "-" + version + ".jar");
                if (!Files.isRegularFile(deploymentJar)) {
                    continue;
                }

                Path runtimeJar = quarkusDir.resolve(artifactId)
                        .resolve(version).resolve(artifactId + "-" + version + ".jar");
                Path devJar = quarkusDir.resolve(artifactId + DEV_SUFFIX)
                        .resolve(version).resolve(artifactId + DEV_SUFFIX + "-" + version + ".jar");

                SkillInfo skill = composeSkillFromExtension(deploymentJar, runtimeJar, devJar,
                        artifactId, metadataOnly);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (IOException e) {
            LOG.debugf("Failed to scan core extension JARs in %s: %s", quarkusDir, e.getMessage());
        }

        if (!skills.isEmpty()) {
            LOG.infof("Found %d skills from core extension JARs (version %s)", skills.size(), version);
        }
        return skills;
    }

    /**
     * Scans non-core extension deployment JARs for skills.
     * Parses the project's pom.xml for dependencies with a groupId other
     * than io.quarkus, looks up deployment, runtime, and dev JARs, and
     * composes skills on-the-fly.
     *
     * @param projectDir        the absolute path to the Quarkus project
     * @param m2Repo            the local Maven repository path
     * @param metadataOnly      if true, only extract frontmatter — content will be null
     * @param includeTransitive if true, include skills from transitive dependencies; if false, only direct dependencies
     * @return list of skills found in non-core extensions, never null
     */
    static List<SkillInfo> scanNonCoreExtensionSkills(String projectDir, Path m2Repo, boolean metadataOnly,
            boolean includeTransitive) {
        if (projectDir == null) {
            return List.of();
        }

        List<DependencyResolver.Dependency> deps = DependencyResolver.resolve(projectDir, includeTransitive);
        if (deps.isEmpty()) {
            return List.of();
        }

        List<SkillInfo> skills = new ArrayList<>();
        for (DependencyResolver.Dependency dep : deps) {
            if (CORE_GROUP_ID.equals(dep.groupId())) {
                continue;
            }
            String groupPath = dep.groupId().replace('.', '/');
            Path deploymentJar;
            Path runtimeJar;
            Path devJar;
            try {
                deploymentJar = m2Repo.resolve(groupPath)
                        .resolve(dep.artifactId() + DEPLOYMENT_SUFFIX)
                        .resolve(dep.version())
                        .resolve(dep.artifactId() + DEPLOYMENT_SUFFIX + "-" + dep.version() + ".jar");
                runtimeJar = m2Repo.resolve(groupPath)
                        .resolve(dep.artifactId())
                        .resolve(dep.version())
                        .resolve(dep.artifactId() + "-" + dep.version() + ".jar");
                devJar = m2Repo.resolve(groupPath)
                        .resolve(dep.artifactId() + DEV_SUFFIX)
                        .resolve(dep.version())
                        .resolve(dep.artifactId() + DEV_SUFFIX + "-" + dep.version() + ".jar");
            } catch (InvalidPathException e) {
                LOG.warnf("Unable to resolve jars for dependency %s", dep);
                continue;
            }
            SkillInfo skill = composeSkillFromExtension(deploymentJar, runtimeJar, devJar,
                    dep.artifactId(), metadataOnly);
            if (skill != null) {
                skills.add(skill);
                LOG.debugf("Found skill from non-core extension %s", dep.artifactId());
            }
        }
        return skills;
    }

    /**
     * Composes a skill from an extension's JARs:
     * raw skill from deployment JAR, metadata from runtime JAR,
     * MCP tools from deployment + dev JARs via Jandex.
     */
    static SkillInfo composeSkillFromExtension(Path deploymentJar, Path runtimeJar, Path devJar,
            String artifactId, boolean metadataOnly) {
        if (!Files.isRegularFile(deploymentJar)) {
            return null;
        }

        try (JarFile depJar = new JarFile(deploymentJar.toFile())) {
            JarEntry skillEntry = depJar.getJarEntry(RAW_SKILL_PATH);
            if (skillEntry == null) {
                return null;
            }

            ExtensionMetadata meta = null;
            if (Files.isRegularFile(runtimeJar)) {
                try (JarFile rtJar = new JarFile(runtimeJar.toFile())) {
                    meta = readExtensionMetadata(rtJar);
                } catch (IOException e) {
                    LOG.debugf("Failed to read runtime metadata from %s: %s", runtimeJar, e.getMessage());
                }
            }

            String skillName = artifactId;
            String description = meta != null ? meta.description : null;
            List<String> categories = meta != null ? meta.categories : null;

            if (metadataOnly) {
                return new SkillInfo(skillName, description, null, SkillMode.ENHANCE, categories, null);
            }

            String rawSkill;
            try (InputStream is = depJar.getInputStream(skillEntry)) {
                rawSkill = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            List<McpToolInfo> tools = discoverMcpTools(depJar, runtimeJar, devJar);
            String content = composeContent(rawSkill, meta, tools, skillName);
            return new SkillInfo(skillName, description, content, SkillMode.ENHANCE, categories, null);
        } catch (IOException e) {
            LOG.debugf("Failed to compose skill from %s: %s", deploymentJar, e.getMessage());
            return null;
        }
    }

    /**
     * Reads extension metadata from {@code META-INF/quarkus-extension.yaml} in a JAR.
     */
    static ExtensionMetadata readExtensionMetadata(JarFile jar) {
        JarEntry yamlEntry = jar.getJarEntry(EXTENSION_YAML_PATH);
        if (yamlEntry == null) {
            return null;
        }
        try (InputStream is = jar.getInputStream(yamlEntry)) {
            String yaml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return parseExtensionYaml(yaml);
        } catch (IOException e) {
            LOG.debugf("Failed to read extension metadata from %s: %s", jar.getName(), e.getMessage());
            return null;
        }
    }

    static ExtensionMetadata parseExtensionYaml(String yaml) {
        ExtensionMetadata meta = new ExtensionMetadata();

        // Unfold YAML double-quoted string line continuations (\<newline><whitespace>)
        // and unescape \<space> to a literal space, so multi-line values like
        // "REST\<LF>  \ Client" become "REST Client"
        yaml = yaml.replaceAll("\\\\\n\\s*", "");
        yaml = yaml.replace("\\ ", " ");

        Matcher m = YAML_NAME.matcher(yaml);
        if (m.find()) {
            meta.name = m.group(1).trim();
        }

        m = YAML_DESCRIPTION.matcher(yaml);
        if (m.find()) {
            meta.description = m.group(1).trim();
        }

        m = YAML_GUIDE.matcher(yaml);
        if (m.find()) {
            meta.guide = m.group(1).trim();
        }

        m = YAML_CATEGORIES_BLOCK.matcher(yaml);
        if (m.find()) {
            String block = m.group(1);
            List<String> cats = new ArrayList<>();
            Matcher itemMatcher = YAML_LIST_ITEM.matcher(block);
            while (itemMatcher.find()) {
                cats.add(itemMatcher.group(1).trim().toLowerCase());
            }
            if (!cats.isEmpty()) {
                meta.categories = List.copyOf(cats);
            }
        }

        return meta;
    }

    /**
     * Composes the skill body from raw skill content, extension metadata,
     * and discovered MCP tools.
     */
    static String composeContent(String rawSkill, ExtensionMetadata meta,
            List<McpToolInfo> tools, String skillName) {
        StringBuilder sb = new StringBuilder();

        if (meta != null) {
            if (meta.name != null) {
                sb.append("# ").append(meta.name).append("\n\n");
            }
            if (meta.description != null) {
                sb.append("> ").append(meta.description).append("\n");
            }
            if (meta.guide != null) {
                sb.append("> Guide: ").append(meta.guide).append("\n");
            }
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
        }

        sb.append(rawSkill.trim()).append("\n");

        if (tools != null && !tools.isEmpty()) {
            sb.append("\n").append(formatMcpToolsSection(tools, skillName));
        }

        return sb.toString();
    }

    // ── MCP tool discovery via Jandex ──────────────────────────────────────

    record McpToolInfo(String name, String description, Map<String, ParameterInfo> parameters) {
    }

    record ParameterInfo(String description, boolean required) {
    }

    /**
     * Discovers MCP tools from an extension's JARs:
     * runtime methods from the dev JAR, build-time tools from the deployment JAR.
     */
    static List<McpToolInfo> discoverMcpTools(JarFile deploymentJar, Path runtimeJar, Path devJar) {
        List<McpToolInfo> tools = new ArrayList<>();

        // Scan dev JAR for @JsonRpcDescription + @DevMCPEnableByDefault methods
        if (devJar != null && Files.isRegularFile(devJar)) {
            try (JarFile jar = new JarFile(devJar.toFile())) {
                tools.addAll(scanRuntimeMcpMethods(jar));
            } catch (IOException e) {
                LOG.debugf("Failed to scan dev JAR %s: %s", devJar, e.getMessage());
            }
        }

        // Scan runtime JAR for the same annotations (some extensions put them here)
        if (runtimeJar != null && Files.isRegularFile(runtimeJar)) {
            try (JarFile jar = new JarFile(runtimeJar.toFile())) {
                tools.addAll(scanRuntimeMcpMethods(jar));
            } catch (IOException e) {
                LOG.debugf("Failed to scan runtime JAR %s: %s", runtimeJar, e.getMessage());
            }
        }

        // Scan deployment JAR for @DevMcpBuildTimeTool annotations
        tools.addAll(scanBuildTimeTools(deploymentJar));

        // Deduplicate by tool name
        Map<String, McpToolInfo> deduplicated = new LinkedHashMap<>();
        for (McpToolInfo tool : tools) {
            deduplicated.putIfAbsent(tool.name(), tool);
        }
        return new ArrayList<>(deduplicated.values());
    }

    /**
     * Scans a JAR for methods annotated with both @JsonRpcDescription and @DevMCPEnableByDefault.
     */
    static List<McpToolInfo> scanRuntimeMcpMethods(JarFile jar) {
        Index index;
        try {
            index = indexJar(jar);
        } catch (IOException e) {
            LOG.debugf("Failed to index JAR %s: %s", jar.getName(), e.getMessage());
            return List.of();
        }

        List<McpToolInfo> tools = new ArrayList<>();
        for (AnnotationInstance ann : index.getAnnotations(JSON_RPC_DESCRIPTION)) {
            if (ann.target().kind() != AnnotationTarget.Kind.METHOD) {
                continue;
            }

            MethodInfo method = ann.target().asMethod();
            if (method.name().equals("<init>")
                    || method.returnType().kind() == Type.Kind.VOID
                    || !java.lang.reflect.Modifier.isPublic(method.flags())) {
                continue;
            }

            if (!method.hasAnnotation(DEV_MCP_ENABLE_BY_DEFAULT)) {
                continue;
            }

            AnnotationValue descValue = ann.value();
            if (descValue == null || descValue.asString().isBlank()) {
                continue;
            }

            Map<String, ParameterInfo> params = new LinkedHashMap<>();
            for (MethodParameterInfo param : method.parameters()) {
                boolean required = !OPTIONAL.equals(param.type().name());
                String paramDesc = null;
                AnnotationInstance paramAnn = param.annotation(JSON_RPC_DESCRIPTION);
                if (paramAnn != null && paramAnn.value() != null) {
                    paramDesc = paramAnn.value().asString();
                }
                params.put(param.name(), new ParameterInfo(paramDesc, required));
            }

            tools.add(new McpToolInfo(method.name(), descValue.asString(),
                    params.isEmpty() ? null : params));
        }
        return tools;
    }

    /**
     * Scans a deployment JAR for @DevMcpBuildTimeTool annotations.
     */
    static List<McpToolInfo> scanBuildTimeTools(JarFile deploymentJar) {
        Index index;
        try {
            index = indexJar(deploymentJar);
        } catch (IOException e) {
            LOG.debugf("Failed to index deployment JAR %s: %s", deploymentJar.getName(), e.getMessage());
            return List.of();
        }

        List<McpToolInfo> tools = new ArrayList<>();

        for (AnnotationInstance ann : index.getAnnotations(DEV_MCP_BUILD_TIME_TOOL)) {
            McpToolInfo tool = buildTimeToolFromAnnotation(ann);
            if (tool != null) {
                tools.add(tool);
            }
        }
        for (AnnotationInstance container : index.getAnnotations(DEV_MCP_BUILD_TIME_TOOLS)) {
            AnnotationValue valueArray = container.value();
            if (valueArray != null) {
                for (AnnotationInstance ann : valueArray.asNestedArray()) {
                    McpToolInfo tool = buildTimeToolFromAnnotation(ann);
                    if (tool != null) {
                        tools.add(tool);
                    }
                }
            }
        }
        return tools;
    }

    private static McpToolInfo buildTimeToolFromAnnotation(AnnotationInstance ann) {
        AnnotationValue nameValue = ann.value("name");
        AnnotationValue descriptionValue = ann.value("description");
        if (nameValue == null || descriptionValue == null) {
            return null;
        }
        String name = nameValue.asString();
        String description = descriptionValue.asString();

        Map<String, ParameterInfo> params = new LinkedHashMap<>();
        AnnotationValue paramsValue = ann.value("params");
        if (paramsValue != null) {
            for (AnnotationInstance paramAnn : paramsValue.asNestedArray()) {
                String paramName = paramAnn.value("name").asString();
                AnnotationValue paramDescValue = paramAnn.value("description");
                String paramDesc = paramDescValue != null ? paramDescValue.asString() : null;
                if (paramDesc != null && paramDesc.isEmpty()) {
                    paramDesc = null;
                }
                AnnotationValue requiredValue = paramAnn.value("required");
                boolean required = requiredValue == null || requiredValue.asBoolean();
                params.put(paramName, new ParameterInfo(paramDesc, required));
            }
        }
        return new McpToolInfo(name, description, params.isEmpty() ? null : params);
    }

    /**
     * Creates a Jandex index from all .class files inside a JAR.
     */
    static Index indexJar(JarFile jar) throws IOException {
        Indexer indexer = new Indexer();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                try (InputStream is = jar.getInputStream(entry)) {
                    indexer.index(is);
                }
            }
        }
        return indexer.complete();
    }

    /**
     * Formats a markdown table listing available Dev MCP tools.
     */
    static String formatMcpToolsSection(List<McpToolInfo> tools, String extensionName) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Available Dev MCP Tools\n\n");
        sb.append("| Tool | Description | Parameters |\n");
        sb.append("|------|-------------|------------|\n");

        for (McpToolInfo tool : tools) {
            String fullName = extensionName + "_" + tool.name();
            sb.append("| `").append(fullName).append("` | ");
            sb.append(escapeMarkdownTable(tool.description())).append(" | ");

            if (tool.parameters() != null && !tool.parameters().isEmpty()) {
                StringJoiner pj = new StringJoiner(", ");
                for (Map.Entry<String, ParameterInfo> param : tool.parameters().entrySet()) {
                    StringBuilder ps = new StringBuilder();
                    ps.append("`").append(param.getKey()).append("`");
                    if (param.getValue().required()) {
                        ps.append(" (required)");
                    }
                    if (param.getValue().description() != null) {
                        ps.append(": ").append(escapeMarkdownTable(param.getValue().description()));
                    }
                    pj.add(ps.toString());
                }
                sb.append(pj);
            } else {
                sb.append("—");
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String escapeMarkdownTable(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    static class ExtensionMetadata {
        String name;
        String description;
        String guide;
        List<String> categories;
    }

    /**
     * Writes a SKILL.md file to the appropriate directory based on scope.
     *
     * @param skillName    the extension name (e.g. "quarkus-rest")
     * @param content      the markdown content (without frontmatter)
     * @param description  optional description for the frontmatter
     * @param categories   optional list of categories for the skill index, or null
     * @param mode         ENHANCE or OVERRIDE
     * @param projectDir   the project directory (used for project-scope writes)
     * @param localSkillsDir user-level skills directory, or null for the default
     * @param projectScope true to write under {@code <projectDir>/.agent/skills/}
     *                     (used by {@code saveSkill} to materialize composed skills),
     *                     false to write under the user-level directory
     * @return the path the file was written to
     */
    static Path writeSkill(String skillName, String content, String description, List<String> categories,
            SkillMode mode, String projectDir, Path localSkillsDir, boolean projectScope) throws IOException {
        return writeSkill(skillName, content, description, categories, mode, projectDir, localSkillsDir, projectScope,
                false);
    }

    static Path writeSkill(String skillName, String content, String description, List<String> categories,
            SkillMode mode, String projectDir, Path localSkillsDir, boolean projectScope, boolean createOnly)
            throws IOException {
        if (skillName == null || !VALID_SKILL_NAME.matcher(skillName).matches()) {
            throw new IllegalArgumentException("Invalid skill name: " + skillName
                    + ". Must contain only letters, digits, dots, hyphens, and underscores.");
        }

        Path baseDir;
        if (projectScope) {
            baseDir = Path.of(projectDir, ".agent", "skills");
        } else {
            baseDir = localSkillsDir != null ? localSkillsDir : DEFAULT_LOCAL_SKILLS_DIR;
        }

        Path skillDir = baseDir.resolve(skillName);
        Files.createDirectories(skillDir);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skillName).append("\n");
        if (description != null && !description.isBlank()) {
            sb.append("description: \"").append(description.replace("\"", "\\\"")).append("\"\n");
        }
        if (categories != null && !categories.isEmpty()) {
            sb.append("categories: \"").append(String.join(", ", categories)).append("\"\n");
        }
        if (!projectScope) {
            sb.append("mode: ").append(mode.name().toLowerCase()).append("\n");
        }
        sb.append("---\n\n");
        sb.append(content);

        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (createOnly) {
            Files.writeString(skillFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } else {
            Files.writeString(skillFile, sb.toString(), StandardCharsets.UTF_8);
        }
        LOG.infof("Wrote skill '%s' to %s", skillName, skillFile);
        return skillFile;
    }

    /**
     * Constructs the path to the aggregated skills JAR in the local Maven repository.
     */
    static Path resolveSkillsJarPath(String version, Path m2Repo) {
        return m2Repo.resolve("io/quarkus")
                .resolve(SKILLS_ARTIFACT_ID)
                .resolve(version)
                .resolve(SKILLS_ARTIFACT_ID + "-" + version + ".jar");
    }

    /**
     * Downloads the skills JAR from a Maven repository and saves it to the local
     * Maven repository path. Resolves the repository URL by checking (in order):
     * project {@code .mvn/maven.config} for a custom settings file,
     * user {@code ~/.m2/settings.xml}, and global {@code ${MAVEN_HOME}/conf/settings.xml}
     * for mirrors. Falls back to Maven Central if no mirror is configured.
     * Returns the path on success, or null on failure.
     */
    Path downloadFromMavenRepo(String version, Path targetPath, String projectDir) {
        if (version.endsWith("-SNAPSHOT")) {
            LOG.debugf("Skipping remote download for SNAPSHOT version %s", version);
            return null;
        }

        MavenRepoInfo repoInfo = resolveMavenRepoInfo(projectDir);
        String artifactPath = "/io/quarkus/" + SKILLS_ARTIFACT_ID
                + "/" + version
                + "/" + SKILLS_ARTIFACT_ID + "-" + version + ".jar";
        String url = repoInfo.url() + artifactPath;

        LOG.debugf("Downloading skills JAR from %s", url);

        try {
            var request = webClient.getAbs(url).timeout(30_000);
            addAuthHeader(request, repoInfo, projectDir);

            var response = request.send().await().atMost(Duration.ofSeconds(35));

            if (response.statusCode() == 200) {
                Files.createDirectories(targetPath.getParent());
                Files.write(targetPath, response.body().getBytes(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                LOG.infof("Downloaded skills JAR to %s", targetPath);
                return targetPath;
            } else {
                LOG.debugf("Maven repo returned HTTP %d for %s", response.statusCode(), url);
                return null;
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugf("Failed to download skills JAR from %s: %s", url, e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the base URL of the Maven repository to use for downloads.
     * Checks the following locations in order for a mirror that applies to
     * Maven Central, returning the first match:
     * <ol>
     *   <li>{@code <projectDir>/.mvn/maven.config} — if it contains {@code -s <path>},
     *       that settings file is checked for mirrors</li>
     *   <li>{@code ~/.m2/settings.xml} — user-level settings</li>
     *   <li>{@code ${MAVEN_HOME}/conf/settings.xml} — global Maven settings</li>
     * </ol>
     * Falls back to Maven Central if no mirror is found in any location.
     *
     * @param projectDir the project directory (may be null)
     */
    static String resolveMavenRepoBaseUrl(String projectDir) {
        return resolveMavenRepoInfo(projectDir).url();
    }

    static MavenRepoInfo resolveMavenRepoInfo(String projectDir) {
        MavenRepoInfo info = findInSettingsFiles(projectDir, "mirror", SkillReader::parseMirrorInfo);
        return info != null ? info : new MavenRepoInfo(MAVEN_CENTRAL_BASE, null, null);
    }

    /**
     * Resolves the local Maven repository path by checking settings files in priority order.
     * Falls back to {@code ~/.m2/repository} if no {@code <localRepository>} is configured.
     */
    static Path resolveLocalMavenRepo(String projectDir) {
        Path repo = findInSettingsFiles(projectDir, "local repository", SkillReader::parseLocalRepository);
        return repo != null ? repo : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /**
     * Walks Maven settings files in priority order, applying the extractor to each:
     * <ol>
     *   <li>{@code .mvn/maven.config} custom settings file</li>
     *   <li>{@code ~/.m2/settings.xml} — user-level settings</li>
     *   <li>{@code ${MAVEN_HOME}/conf/settings.xml} — global Maven settings</li>
     * </ol>
     * Returns the first non-null result, or null if none matched.
     */
    static <T> T findInSettingsFiles(String projectDir, String description, Function<Path, T> extractor) {
        // 1. Check .mvn/maven.config for a custom settings file
        if (projectDir != null) {
            Path customSettings = parseSettingsFromMvnConfig(Path.of(projectDir));
            if (customSettings != null && Files.isRegularFile(customSettings)) {
                T result = extractor.apply(customSettings);
                if (result != null) {
                    LOG.debugf("Using %s from .mvn/maven.config settings: %s", description, result);
                    return result;
                }
            }
        }

        // 2. Check user-level settings.xml
        Path userSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        if (Files.isRegularFile(userSettings)) {
            T result = extractor.apply(userSettings);
            if (result != null) {
                LOG.debugf("Using %s from user settings.xml: %s", description, result);
                return result;
            }
        }

        // 3. Check global Maven settings.xml
        Path globalSettings = resolveGlobalSettingsPath();
        if (globalSettings != null && Files.isRegularFile(globalSettings)) {
            T result = extractor.apply(globalSettings);
            if (result != null) {
                LOG.debugf("Using %s from global settings.xml: %s", description, result);
                return result;
            }
        }

        return null;
    }

    /**
     * Parses {@code .mvn/maven.config} in the project directory for a {@code -s}
     * or {@code --settings} flag pointing to a custom settings file.
     * Returns the resolved path, or null if not found.
     */
    static Path parseSettingsFromMvnConfig(Path projectDir) {
        Path configFile = projectDir.resolve(".mvn/maven.config");
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            String[] tokens = content.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                String settingsValue = null;
                if ((token.equals("-s") || token.equals("--settings")) && i + 1 < tokens.length) {
                    settingsValue = tokens[i + 1];
                } else if (token.startsWith("-s=")) {
                    settingsValue = token.substring(3);
                } else if (token.startsWith("--settings=")) {
                    settingsValue = token.substring(11);
                }
                if (settingsValue != null) {
                    Path settingsPath = Path.of(settingsValue);
                    if (!settingsPath.isAbsolute()) {
                        settingsPath = projectDir.resolve(settingsPath);
                    }
                    return settingsPath.normalize();
                }
            }
        } catch (IOException e) {
            LOG.debugf("Failed to read .mvn/maven.config: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the path to the global Maven {@code settings.xml}.
     * Checks {@code MAVEN_HOME} and {@code M2_HOME} environment variables,
     * then falls back to the {@code maven.home} system property.
     */
    static Path resolveGlobalSettingsPath() {
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null) {
            mavenHome = System.getenv("M2_HOME");
        }
        if (mavenHome == null) {
            mavenHome = System.getProperty("maven.home");
        }
        if (mavenHome != null) {
            return Path.of(mavenHome, "conf", "settings.xml");
        }
        return null;
    }

    /**
     * Parses {@code settings.xml} for a mirror that applies to Maven Central.
     * Returns the mirror URL (without trailing slash), or null if none found.
     */
    static String parseMirrorUrl(Path settingsFile) {
        MavenRepoInfo info = parseMirrorInfo(settingsFile);
        return info != null ? info.url() : null;
    }

    static MavenRepoInfo parseMirrorInfo(Path settingsFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(settingsFile.toFile());

            NodeList mirrors = doc.getElementsByTagName("mirror");
            for (int i = 0; i < mirrors.getLength(); i++) {
                Element mirror = (Element) mirrors.item(i);
                String mirrorOf = getChildText(mirror, "mirrorOf");
                String url = getChildText(mirror, "url");

                if (mirrorOf != null && url != null && mirrorOfMatchesCentral(mirrorOf)) {
                    String cleanUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                    String id = getChildText(mirror, "id");
                    ServerCredentials credentials = id != null ? findServerInDocument(doc, id) : null;
                    return new MavenRepoInfo(cleanUrl, id, credentials);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse settings.xml at %s: %s", settingsFile, e.getMessage());
        }
        return null;
    }

    static ServerCredentials resolveServerCredentials(String projectDir, String serverId) {
        if (serverId == null) {
            return null;
        }
        return findInSettingsFiles(projectDir, "server credentials",
                path -> parseServerCredentials(path, serverId));
    }

    static ServerCredentials parseServerCredentials(Path settingsFile, String serverId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(settingsFile.toFile());
            return findServerInDocument(doc, serverId);
        } catch (Exception e) {
            LOG.warnf("Failed to parse server credentials from %s: %s", settingsFile, e.getMessage());
        }
        return null;
    }

    private static ServerCredentials findServerInDocument(Document doc, String serverId) {
        NodeList servers = doc.getElementsByTagName("server");
        for (int i = 0; i < servers.getLength(); i++) {
            Element server = (Element) servers.item(i);
            String id = getChildText(server, "id");
            if (serverId.equals(id)) {
                String username = getChildText(server, "username");
                String password = getChildText(server, "password");
                if (username != null && password != null) {
                    return new ServerCredentials(username, password);
                }
            }
        }
        return null;
    }

    static void addAuthHeader(io.vertx.mutiny.ext.web.client.HttpRequest<?> request, MavenRepoInfo repoInfo,
            String projectDir) {
        ServerCredentials credentials = repoInfo.credentials();
        if (credentials == null) {
            credentials = resolveServerCredentials(projectDir, repoInfo.serverId());
        }
        if (credentials != null) {
            request.putHeader("Authorization", buildAuthHeader(credentials));
        }
    }

    static String buildAuthHeader(ServerCredentials credentials) {
        if (credentials.password().startsWith("{") && credentials.password().endsWith("}")) {
            LOG.warnf("Password for user '%s' appears to be Maven-encrypted; encrypted passwords are not supported",
                    credentials.username());
        }
        String value = credentials.username() + ":" + credentials.password();
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses {@code settings.xml} for a {@code <localRepository>} element.
     * Returns the configured path, or null if not found or blank.
     */
    static Path parseLocalRepository(Path settingsFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(settingsFile.toFile());

            NodeList nodes = doc.getElementsByTagName("localRepository");
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    String path = text.trim().replace("${user.home}", System.getProperty("user.home"));
                    return Path.of(path);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse settings.xml at %s: %s", settingsFile, e.getMessage());
        }
        return null;
    }

    /**
     * Checks whether a {@code mirrorOf} value applies to Maven Central.
     * Handles common patterns: {@code central}, {@code *}, {@code external:*},
     * and comma-separated lists containing these values (excluding negations).
     */
    static boolean mirrorOfMatchesCentral(String mirrorOf) {
        String trimmed = mirrorOf.trim();

        // Exact matches
        if (trimmed.equals("*") || trimmed.equals("central") || trimmed.equals("external:*")) {
            return true;
        }

        // Comma-separated: check for central or wildcard, but respect negations like !central
        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            boolean matched = false;
            for (String part : parts) {
                String p = part.trim();
                if (p.equals("!central")) {
                    return false;
                }
                if (p.equals("*") || p.equals("central") || p.equals("external:*")) {
                    matched = true;
                }
            }
            return matched;
        }

        return false;
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() > 0) {
            String text = children.item(0).getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }
}
