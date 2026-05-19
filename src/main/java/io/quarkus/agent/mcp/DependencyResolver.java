package io.quarkus.agent.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Resolves project dependencies (both direct and transitive) with their versions.
 * Tries fast local XML/properties parsing first for direct dependencies, then falls back to
 * shelling out to Maven/Gradle for the full dependency tree including transitives.
 * Results are cached per project directory.
 */
public final class DependencyResolver {

    private static final Logger LOG = Logger.getLogger(DependencyResolver.class);

    private DependencyResolver() {
    }

    record Dependency(String groupId, String artifactId, String version) {
    }

    private static final ConcurrentHashMap<String, List<Dependency>> CACHE = new ConcurrentHashMap<>();

    // Maven dependency:list output: "   groupId:artifactId:type:version:scope"
    private static final Pattern MAVEN_DEP_LINE = Pattern.compile(
            "^\\s+(\\S+):(\\S+):\\S+:(\\S+):\\S+$");

    // Maven dependency:tree output: "[+|\-] groupId:artifactId:type:version:scope"
    // Handles tree structure with +-, |, and \ characters
    private static final Pattern MAVEN_TREE_LINE = Pattern.compile(
            "^[+|\\\\\\s-]+\\s+(\\S+):(\\S+):\\S+:(\\S+):\\S+$");

    // Gradle dependency (direct and transitive): "[+|\|\\-] group:artifact:version"
    // Handles tree structure with +---, \---, |, and spaces for indentation
    // Also handles "-> resolvedVersion" and constraint markers like (c), (*), (n)
    private static final Pattern GRADLE_DEP_LINE = Pattern.compile(
            "^[+|\\\\\\s-]+\\s+(\\S+):(\\S+):(\\S+?)(?:\\s+->\\s+(\\S+))?(?:\\s+\\(.*\\))?\\s*$");

    // Gradle dependency without version (BOM-managed): "[+|\|\\-] group:artifact (c)" or similar
    private static final Pattern GRADLE_DEP_NO_VERSION = Pattern.compile(
            "^[+|\\\\\\s-]+\\s+(\\S+):(\\S+)(?:\\s+\\(.*\\))?\\s*$");

    /**
     * Resolve all dependencies (direct and transitive) for the given project directory.
     * Returns a cached result if available. All returned dependencies
     * have non-null groupId, artifactId, and version.
     */
    public static List<Dependency> resolve(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) {
            return List.of();
        }
        List<Dependency> cached = CACHE.get(projectDir);
        if (cached != null) {
            return cached;
        }

        List<Dependency> deps = doResolve(projectDir);
        CACHE.put(projectDir, deps);
        return deps;
    }

    public static void invalidate(String projectDir) {
        if (projectDir != null) {
            CACHE.remove(projectDir);
        }
    }

    static void clearAll() {
        CACHE.clear();
    }

    private static List<Dependency> doResolve(String projectDir) {
        File dir = new File(projectDir);
        if (!dir.isDirectory()) {
            return List.of();
        }

        // Maven project
        if (new File(dir, "pom.xml").isFile()) {
            return resolveForMaven(dir);
        }

        // Gradle project
        if (new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile()) {
            return resolveForGradle(dir);
        }

        return List.of();
    }

    // ── Maven resolution ─────────────────────────────────────────────────────

    private static List<Dependency> resolveForMaven(File dir) {
        List<Dependency> xmlDeps = parseMavenPom(dir);

        boolean hasUnresolved = xmlDeps.stream().anyMatch(d -> d.version() == null);
        if (!hasUnresolved) {
            return xmlDeps;
        }

        // Fast path had unresolved versions — shell out to Maven
        List<Dependency> buildToolDeps = resolveViaMaven(dir);
        if (buildToolDeps.isEmpty()) {
            // Fallback failed, return only resolved XML deps
            return xmlDeps.stream().filter(d -> d.version() != null).toList();
        }

        return mergeMavenResults(xmlDeps, buildToolDeps);
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
                        mvnCmd, "dependency:tree",
                        "-DincludeScope=compile",
                        "-DoutputType=text",
                        "-q", "-DoutputFile=" + tempFile.toAbsolutePath())
                        .directory(dir)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                String ignored = ProcessUtils.runAndCapture(pb, 60, TimeUnit.SECONDS);
                if (ignored == null) {
                    return List.of();
                }
                String output = Files.readString(tempFile);
                return parseMavenDependencyTree(output);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            LOG.debugf("Failed to create temp file for Maven dependency resolution: %s", e.getMessage());
            return List.of();
        }
    }

    static List<Dependency> parseMavenDependencyList(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<Dependency> deps = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher m = MAVEN_DEP_LINE.matcher(line);
            if (m.matches()) {
                deps.add(new Dependency(m.group(1), m.group(2), m.group(3)));
            }
        }
        return deps;
    }

    static List<Dependency> parseMavenDependencyTree(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<Dependency> deps = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher m = MAVEN_TREE_LINE.matcher(line);
            if (m.matches()) {
                deps.add(new Dependency(m.group(1), m.group(2), m.group(3)));
            }
        }
        return deps;
    }

    /**
     * Merges XML-parsed deps with build-tool-resolved deps.
     * Returns all build tool dependencies (direct and transitive).
     * If a dependency was in the XML parse with a version, uses that version,
     * otherwise uses the version from the build tool.
     */
    private static List<Dependency> mergeMavenResults(List<Dependency> xmlDeps, List<Dependency> buildToolDeps) {
        // Build a map of XML dependencies that have versions
        Map<String, String> xmlVersions = new HashMap<>();
        for (Dependency dep : xmlDeps) {
            if (dep.version() != null) {
                xmlVersions.put(dep.groupId() + ":" + dep.artifactId(), dep.version());
            }
        }

        // Return all build tool dependencies, preferring XML versions where available
        List<Dependency> merged = new ArrayList<>();
        for (Dependency dep : buildToolDeps) {
            String key = dep.groupId() + ":" + dep.artifactId();
            String version = xmlVersions.getOrDefault(key, dep.version());
            if (version != null) {
                merged.add(new Dependency(dep.groupId(), dep.artifactId(), version));
            }
        }
        return merged;
    }

    // ── Gradle resolution ────────────────────────────────────────────────────

    private static List<Dependency> resolveForGradle(File dir) {
        String gradleCmd = ProcessUtils.resolveGradleCommand(dir);
        ProcessBuilder pb = new ProcessBuilder(
                gradleCmd, "dependencies",
                "--configuration", "runtimeClasspath",
                "-q", "--console=plain")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String output = ProcessUtils.runAndCapture(pb, 60, TimeUnit.SECONDS);
        return parseGradleDependencyTree(output);
    }

    static List<Dependency> parseGradleDependencyTree(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<Dependency> deps = new ArrayList<>();
        for (String line : output.split("\n")) {
            // Parse all dependencies (direct and transitive) from the tree
            Matcher m = GRADLE_DEP_LINE.matcher(line);
            if (m.matches()) {
                String version = m.group(4) != null ? m.group(4) : m.group(3);
                deps.add(new Dependency(m.group(1), m.group(2), version));
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
