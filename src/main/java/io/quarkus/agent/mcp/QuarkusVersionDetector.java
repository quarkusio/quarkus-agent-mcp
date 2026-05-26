package io.quarkus.agent.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Detects the Quarkus version used by a project from its build files.
 * Validates that detected versions match a semver-like pattern
 * to prevent injection via crafted build files.
 * Results are cached per project directory to avoid re-reading build files on every call.
 */
public final class QuarkusVersionDetector {

    private static final Logger LOG = Logger.getLogger(QuarkusVersionDetector.class);

    private QuarkusVersionDetector() {
    }

    // Cache: projectDir -> detected version (null values stored as empty string)
    private static final ConcurrentHashMap<String, String> VERSION_CACHE = new ConcurrentHashMap<>();
    private static final String NULL_SENTINEL = "";

    // Only allow versions like 3.21.2, 3.21.2.Final, 3.21.2-SNAPSHOT, 3.21.2.CR1, 999-SNAPSHOT
    private static final Pattern VALID_VERSION = Pattern.compile(
            "^[0-9]+(\\.[0-9]+)*([.\\-][A-Za-z0-9]+)*$");

    // Maven: <quarkus.platform.version>3.21.2</quarkus.platform.version>
    private static final Pattern MAVEN_PLATFORM_VERSION = Pattern.compile(
            "<quarkus\\.platform\\.version>([^<]+)</quarkus\\.platform\\.version>");

    // Maven fallback: <quarkus-plugin.version>3.21.2</quarkus-plugin.version>
    private static final Pattern MAVEN_PLUGIN_VERSION = Pattern.compile(
            "<quarkus-plugin\\.version>([^<]+)</quarkus-plugin\\.version>");

    // Gradle: quarkusPlatformVersion=3.21.2 (in gradle.properties)
    private static final Pattern GRADLE_PLATFORM_VERSION = Pattern.compile(
            "quarkusPlatformVersion\\s*=\\s*(.+)");

    // Maven dependency:list output line for io.quarkus:quarkus-core
    private static final Pattern MAVEN_QUARKUS_CORE_DEP = Pattern.compile(
            "^\\s+io\\.quarkus:quarkus-core:\\S+:(\\S+):\\S+.*$");

    // Gradle dependency tree output line for io.quarkus:quarkus-core
    private static final Pattern GRADLE_QUARKUS_CORE_DEP = Pattern.compile(
            "^[+\\\\]---\\s+io\\.quarkus:quarkus-core:(\\S+?)(?:\\s+->\\s+(\\S+))?(?:\\s+\\(.*\\))?\\s*$");

    private static final Pattern MAVEN_PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    private static final String QUARKUS_CORE_GROUP = "io.quarkus";
    private static final String QUARKUS_CORE_ARTIFACT = "quarkus-core";

    /**
     * Detect the Quarkus version from the given project directory.
     * Returns null if not found or if the detected version doesn't match
     * a valid semver-like pattern (to prevent injection via crafted build files).
     *
     * @return the detected version string, or null if not found or invalid
     */
    public static String detect(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) {
            return null;
        }
        String cached = VERSION_CACHE.get(projectDir);
        if (cached != null) {
            return NULL_SENTINEL.equals(cached) ? null : cached;
        }

        String version = doDetect(projectDir);
        VERSION_CACHE.put(projectDir, version != null ? version : NULL_SENTINEL);
        return version;
    }

    public static void invalidate(String projectDir) {
        if (projectDir != null) {
            VERSION_CACHE.remove(projectDir);
        }
    }

    private static String doDetect(String projectDir) {
        File dir = new File(projectDir);
        if (!dir.isDirectory()) {
            return null;
        }

        String version = null;

        // Try Maven pom.xml
        File pomFile = new File(dir, "pom.xml");
        if (pomFile.isFile()) {
            version = detectFromMaven(pomFile);
        }

        // Try Gradle gradle.properties
        if (version == null) {
            File gradleProps = new File(dir, "gradle.properties");
            if (gradleProps.isFile()) {
                version = detectFromGradleProperties(gradleProps);
            }
        }

        // Fallback: shell out to Maven/Gradle to resolve inherited properties
        if (version == null && pomFile.isFile()) {
            version = detectFromMavenBuildTool(dir);
        }
        if (version == null) {
            if (new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile()) {
                version = detectFromGradleBuildTool(dir);
            }
        }

        // Fallback: detect from quarkus-core dependency in pom.xml (fast, local XML parse)
        if (version == null && pomFile.isFile()) {
            version = detectFromMavenQuarkusCoreDep(pomFile);
        }

        // Fallback: shell out to Maven dependency:list and find quarkus-core version
        if (version == null && pomFile.isFile()) {
            version = detectFromMavenDependencyList(dir);
        }

        // Fallback: shell out to Gradle dependencies and find quarkus-core version
        if (version == null) {
            if (new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile()) {
                version = detectFromGradleDependencyTree(dir);
            }
        }

        if (version == null) {
            return null;
        }

        // Validate the version looks like a real semver string
        if (!VALID_VERSION.matcher(version).matches()) {
            LOG.warnf("Detected Quarkus version '%s' in %s does not match expected format — ignoring.",
                    version, projectDir);
            return null;
        }

        LOG.infof("Detected Quarkus version %s in %s", version, projectDir);
        return version;
    }

    private static String detectFromMaven(File pomFile) {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            Matcher m = MAVEN_PLATFORM_VERSION.matcher(content);
            if (m.find()) {
                return m.group(1).trim();
            }
            m = MAVEN_PLUGIN_VERSION.matcher(content);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (IOException e) {
            LOG.debugf("Failed to read pom.xml: %s", e.getMessage());
        }
        return null;
    }

    private static String detectFromGradleProperties(File propsFile) {
        try {
            String content = Files.readString(propsFile.toPath(), StandardCharsets.UTF_8);
            Matcher m = GRADLE_PLATFORM_VERSION.matcher(content);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (IOException e) {
            LOG.debugf("Failed to read gradle.properties: %s", e.getMessage());
        }
        return null;
    }

    private static String detectFromMavenBuildTool(File dir) {
        String mvnCmd = ProcessUtils.resolveMavenCommand(dir);
        ProcessBuilder pb = new ProcessBuilder(
                mvnCmd, "help:evaluate",
                "-Dexpression=quarkus.platform.version",
                "-q", "-DforceStdout", "-N")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String output = ProcessUtils.runAndCapture(pb, 30, TimeUnit.SECONDS);
        return parseMavenEvaluateOutput(output);
    }

    private static String detectFromGradleBuildTool(File dir) {
        String gradleCmd = ProcessUtils.resolveGradleCommand(dir);
        ProcessBuilder pb = new ProcessBuilder(
                gradleCmd, "properties",
                "-q", "--property", "quarkusPlatformVersion")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String output = ProcessUtils.runAndCapture(pb, 30, TimeUnit.SECONDS);
        return parseGradlePropertiesOutput(output);
    }

    static String parseMavenEvaluateOutput(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null") || trimmed.contains("${")) {
            return null;
        }
        return trimmed;
    }

    static String parseGradlePropertiesOutput(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("quarkusPlatformVersion:")) {
                String value = trimmed.substring("quarkusPlatformVersion:".length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String detectFromMavenQuarkusCoreDep(File pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pomFile);

            Map<String, String> properties = parsePropertiesFromDoc(doc);

            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element depEl = (Element) depNodes.item(i);
                if (shouldSkipDependency(depEl)) {
                    continue;
                }
                String groupId = getChildText(depEl, "groupId");
                String artifactId = getChildText(depEl, "artifactId");
                String version = getChildText(depEl, "version");

                if (groupId != null) {
                    groupId = resolveProperty(groupId, properties);
                }
                if (artifactId != null) {
                    artifactId = resolveProperty(artifactId, properties);
                }
                if (version != null) {
                    version = resolveProperty(version, properties);
                }

                if (QUARKUS_CORE_GROUP.equals(groupId) && QUARKUS_CORE_ARTIFACT.equals(artifactId)
                        && version != null && !version.contains("${")) {
                    LOG.debugf("Found quarkus-core dependency version %s in pom.xml", version);
                    return version;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to parse pom.xml for quarkus-core dependency: %s", e.getMessage());
        }
        return null;
    }

    private static String detectFromMavenDependencyList(File dir) {
        String mvnCmd = ProcessUtils.resolveMavenCommand(dir);
        ProcessBuilder pb = new ProcessBuilder(
                mvnCmd, "dependency:list",
                "-DincludeScope=compile",
                "-q", "-DforceStdout", "-N")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String output = ProcessUtils.runAndCapture(pb, 30, TimeUnit.SECONDS);
        return parseMavenDependencyListForQuarkusCore(output);
    }

    static String parseMavenDependencyListForQuarkusCore(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\\r?\\n")) {
            Matcher m = MAVEN_QUARKUS_CORE_DEP.matcher(line);
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String detectFromGradleDependencyTree(File dir) {
        String gradleCmd = ProcessUtils.resolveGradleCommand(dir);
        ProcessBuilder pb = new ProcessBuilder(
                gradleCmd, "dependencies",
                "--configuration", "runtimeClasspath",
                "-q", "--console=plain")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String output = ProcessUtils.runAndCapture(pb, 30, TimeUnit.SECONDS);
        return parseGradleDependencyTreeForQuarkusCore(output);
    }

    static String parseGradleDependencyTreeForQuarkusCore(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\n")) {
            Matcher m = GRADLE_QUARKUS_CORE_DEP.matcher(line);
            if (m.matches()) {
                return m.group(2) != null ? m.group(2) : m.group(1);
            }
        }
        return null;
    }

    private static Map<String, String> parsePropertiesFromDoc(Document doc) {
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

    private static String resolveProperty(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String resolved = value;
        Matcher m = MAVEN_PROPERTY_REF.matcher(value);
        while (m.find()) {
            String propName = m.group(1);
            String propValue = properties.get(propName);
            if (propValue != null) {
                resolved = resolved.replace(m.group(0), propValue);
            }
        }
        return resolved;
    }

    private static boolean shouldSkipDependency(Element el) {
        Node parent = el.getParentNode();
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
