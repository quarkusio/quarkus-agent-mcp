package io.quarkus.agent.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Resolves project dependencies with their versions.
 * Tries fast local XML/properties parsing first, then falls back to
 * shelling out to Maven/Gradle for inherited or BOM-managed versions.
 * Results are cached per project directory.
 */
public final class DependencyResolver {

    private static final Logger LOG = Logger.getLogger(DependencyResolver.class);

    private DependencyResolver() {
    }

    record Dependency(String groupId, String artifactId, String version) {
    }

    private static final ConcurrentHashMap<String, List<Dependency>> CACHE = new ConcurrentHashMap<>();

    // Maven dependency:list output, either from stdout with [INFO] prefix
    // ("[INFO]    groupId:artifactId:type:version:scope") or from -DoutputFile
    // ("   groupId:artifactId:type:version:scope").
    // May be suffixed with ANSI codes and module info.
    private static final Pattern MAVEN_DEP_LINE = Pattern.compile(
            "^(?:\\[INFO\\])?\\s+([^\\s:]+):([^\\s:]+):jar(?::[^\\s:]+)?:([^\\s:]+):(?:compile|provided|runtime|test|system|import).*$");

    // Gradle dependency: "+--- group:artifact:version" or "\--- group:artifact:version"
    // Also handles transitive deps with leading pipes/spaces: "|    +---" or "     +---"
    // Also handles "-> resolvedVersion" and constraint markers like (c), (*), (n)
    private static final Pattern GRADLE_DEP_LINE = Pattern.compile(
            "^[|\\s]*[+\\\\]---\\s+(\\S+):(\\S+):(\\S+?)(?:\\s+->\\s+(\\S+))?(?:\\s+\\(.*\\))?\\s*$");

    // Gradle dependency without version (BOM-managed): "+--- group:artifact (c)" or similar
    private static final Pattern GRADLE_DEP_NO_VERSION = Pattern.compile(
            "^[|\\s]*[+\\\\]---\\s+(\\S+):(\\S+)(?:\\s+\\(.*\\))?\\s*$");

    /**
     * Resolve all direct dependencies for the given project directory.
     * Returns a cached result if available. All returned dependencies
     * have non-null groupId, artifactId, and version.
     */
    public static List<Dependency> resolve(String projectDir) {
        return resolve(projectDir, false);
    }

    /**
     * Resolve dependencies for the given project directory.
     * Returns a cached result if available. All returned dependencies
     * have non-null groupId, artifactId, and version.
     *
     * @param projectDir        the absolute path to the project directory
     * @param includeTransitive if true, include transitive dependencies; if false, only direct dependencies
     * @return list of resolved dependencies, never null
     */
    public static List<Dependency> resolve(String projectDir, boolean includeTransitive) {
        if (projectDir == null || projectDir.isBlank()) {
            return List.of();
        }
        String cacheKey = projectDir + ":transitive=" + includeTransitive;
        List<Dependency> cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<Dependency> deps = doResolve(projectDir, includeTransitive);
        if (!deps.isEmpty()) {
            CACHE.put(cacheKey, deps);
        }
        return deps;
    }

    public static void invalidate(String projectDir) {
        if (projectDir != null) {
            // Remove both cache entries (with and without transitive)
            CACHE.remove(projectDir + ":transitive=false");
            CACHE.remove(projectDir + ":transitive=true");
        }
    }

    static void clearAll() {
        CACHE.clear();
    }

    private static List<Dependency> doResolve(String projectDir, boolean includeTransitive) {
        File dir = new File(projectDir);
        if (!dir.isDirectory()) {
            return List.of();
        }

        // Maven project
        if (new File(dir, "pom.xml").isFile()) {
            return resolveForMaven(dir, includeTransitive);
        }

        // Gradle project
        if (new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile()) {
            return resolveForGradle(dir, includeTransitive);
        }

        return List.of();
    }

    // ── Maven resolution ─────────────────────────────────────────────────────

    private static List<Dependency> resolveForMaven(File dir, boolean includeTransitive) {
        List<Dependency> xmlDeps = parseMavenPom(dir);

        boolean hasUnresolved = xmlDeps.stream().anyMatch(d -> d.version() == null);
        if (!hasUnresolved && !includeTransitive) {
            return xmlDeps;
        }

        // Fast path had unresolved versions or we need transitive deps — shell out to Maven
        List<Dependency> buildToolDeps = resolveViaMaven(dir);
        if (buildToolDeps == null || buildToolDeps.isEmpty()) {
            // Build tool resolution failed or returned nothing — return only resolved XML deps
            return xmlDeps.stream().filter(d -> d.version() != null).toList();
        }

        return mergeMavenResults(xmlDeps, buildToolDeps, includeTransitive);
    }

    /**
     * Parses dependencies from pom.xml with local property resolution.
     * Dependencies with unresolvable versions are included with version=null
     * so the caller can decide whether to fall back to the build tool.
     */
    static List<Dependency> parseMavenPom(File dir) {
        Path pomFile = dir.toPath().resolve("pom.xml");
        if (!Files.isRegularFile(pomFile)) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pomFile.toFile());

            Map<String, String> properties = parseProperties(doc);

            List<Dependency> deps = new ArrayList<>();
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element depEl = (Element) depNodes.item(i);
                if (isNestedInPluginOrExclusion(depEl)) {
                    continue;
                }
                String groupId = getChildText(depEl, "groupId");
                String artifactId = getChildText(depEl, "artifactId");
                String version = getChildText(depEl, "version");

                if (groupId == null || artifactId == null) {
                    continue;
                }

                groupId = resolveProperty(groupId, properties);
                artifactId = resolveProperty(artifactId, properties);
                if (version != null) {
                    version = resolveProperty(version, properties);
                }

                // Keep unresolved versions as null so caller can trigger fallback
                if (version != null && version.contains("${")) {
                    version = null;
                }

                deps.add(new Dependency(groupId, artifactId, version));
            }
            return deps;
        } catch (Exception e) {
            LOG.debugf("Failed to parse pom.xml at %s: %s", pomFile, e.getMessage());
            return List.of();
        }
    }

    private static List<Dependency> resolveViaMaven(File dir) {
        String mvnCmd = ProcessUtils.resolveMavenCommand(dir);
        try {
            Path tempFile = Files.createTempFile("mvn-deps-", ".txt");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        mvnCmd, "dependency:list",
                        "-DincludeScope=compile")
                        .directory(dir)
                        .redirectOutput(tempFile.toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                try {
                    if (!process.waitFor(180, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        LOG.debugf("Maven dependency:list timed out for %s", dir);
                        return null;
                    }
                    if (process.exitValue() != 0) {
                        LOG.debugf("Maven dependency:list exited with code %d for %s", process.exitValue(), dir);
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    return null;
                }
                String output = Files.readString(tempFile);
                return parseMavenDependencyList(output);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            LOG.debugf("Failed Maven dependency resolution for %s: %s", dir, e.getMessage());
            return null;
        }
    }

    static List<Dependency> parseMavenDependencyList(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<Dependency> deps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output.split("\\R")) {
            Matcher m = MAVEN_DEP_LINE.matcher(line);
            if (m.matches()) {
                String key = m.group(1) + ":" + m.group(2);
                if (seen.add(key)) {
                    deps.add(new Dependency(m.group(1), m.group(2), m.group(3)));
                }
            }
        }
        return deps;
    }

    /**
     * Merges XML-parsed deps with build-tool-resolved deps.
     * When includeTransitive is false, uses the XML parse as the source of which dependencies are direct,
     * and fills in missing versions from the build-tool output.
     * When includeTransitive is true, returns all dependencies from the build-tool output.
     */
    private static List<Dependency> mergeMavenResults(List<Dependency> xmlDeps, List<Dependency> buildToolDeps,
            boolean includeTransitive) {
        if (includeTransitive) {
            // Return all dependencies from build tool (includes transitive)
            return buildToolDeps;
        }

        // Original behavior: only direct dependencies from XML
        Map<String, String> versionLookup = new HashMap<>();
        for (Dependency dep : buildToolDeps) {
            versionLookup.put(dep.groupId() + ":" + dep.artifactId(), dep.version());
        }

        List<Dependency> merged = new ArrayList<>();
        for (Dependency dep : xmlDeps) {
            String version = dep.version();
            if (version == null) {
                version = versionLookup.get(dep.groupId() + ":" + dep.artifactId());
            }
            if (version != null) {
                merged.add(new Dependency(dep.groupId(), dep.artifactId(), version));
            }
        }
        return merged;
    }

    // ── Gradle resolution ────────────────────────────────────────────────────

    private static List<Dependency> resolveForGradle(File dir, boolean includeTransitive) {
        String gradleCmd = ProcessUtils.resolveGradleCommand(dir);
        ProcessBuilder pb = new ProcessBuilder(
                gradleCmd, "dependencies",
                "--configuration", "runtimeClasspath",
                "-q", "--console=plain")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String output = ProcessUtils.runAndCapture(pb, 60, TimeUnit.SECONDS);
        return parseGradleDependencyTree(output, includeTransitive);
    }

    static List<Dependency> parseGradleDependencyTree(String output) {
        return parseGradleDependencyTree(output, false);
    }

    static List<Dependency> parseGradleDependencyTree(String output, boolean includeTransitive) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<Dependency> deps = new ArrayList<>();
        // Track seen dependencies to avoid duplicates
        Set<String> seen = new HashSet<>();

        for (String line : output.split("\n")) {
            // When includeTransitive is false, only parse root-level dependencies
            // (no leading spaces before +--- or \---)
            if (!includeTransitive && !line.startsWith("+---") && !line.startsWith("\\---")) {
                continue;
            }

            // When includeTransitive is true, parse all dependency lines
            // (including nested ones with leading pipes and spaces)
            Matcher m = GRADLE_DEP_LINE.matcher(line);
            if (m.matches()) {
                String groupId = m.group(1);
                String artifactId = m.group(2);
                String version = m.group(4) != null ? m.group(4) : m.group(3);
                String key = groupId + ":" + artifactId;

                // Skip duplicates (same artifact can appear multiple times in the tree)
                if (!seen.add(key)) {
                    continue;
                }

                deps.add(new Dependency(groupId, artifactId, version));
                continue;
            }
            // Try no-version pattern (BOM-managed without version in output)
            Matcher m2 = GRADLE_DEP_NO_VERSION.matcher(line);
            if (m2.matches()) {
                LOG.debugf("Gradle dependency without version: %s:%s", m2.group(1), m2.group(2));
            }
        }
        return deps;
    }

    // ── XML helpers (shared with Maven POM parsing) ──────────────────────────

    private static Map<String, String> parseProperties(Document doc) {
        Map<String, String> props = new HashMap<>();
        NodeList propsNodes = doc.getElementsByTagName("properties");
        if (propsNodes.getLength() > 0) {
            Element propsEl = (Element) propsNodes.item(0);
            NodeList children = propsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element el) {
                    props.put(el.getTagName(), el.getTextContent().trim());
                }
            }
        }
        return props;
    }

    static String resolveProperty(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String resolved = value;
        Pattern propPattern = Pattern.compile("\\$\\{([^}]+)}");
        Matcher m = propPattern.matcher(value);
        while (m.find()) {
            String propName = m.group(1);
            String propValue = properties.get(propName);
            if (propValue != null) {
                resolved = resolved.replace(m.group(0), propValue);
            }
        }
        return resolved;
    }

    private static boolean isNestedInPluginOrExclusion(Element el) {
        org.w3c.dom.Node parent = el.getParentNode();
        while (parent instanceof Element parentEl) {
            String tag = parentEl.getTagName();
            if ("plugin".equals(tag) || "exclusions".equals(tag) || "dependencyManagement".equals(tag)) {
                return true;
            }
            parent = parentEl.getParentNode();
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
