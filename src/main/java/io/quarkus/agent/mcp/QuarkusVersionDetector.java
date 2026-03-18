package io.quarkus.agent.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * Detects the Quarkus version used by a project from its build files.
 * Validates that detected versions match a semver-like pattern
 * to prevent injection via crafted build files.
 * Results are cached per project directory to avoid re-reading build files on every call.
 */
public class QuarkusVersionDetector {

    private static final Logger LOG = Logger.getLogger(QuarkusVersionDetector.class);

    // Cache: projectDir -> detected version (null values stored as empty string)
    private static final ConcurrentHashMap<String, String> VERSION_CACHE = new ConcurrentHashMap<>();
    private static final String NULL_SENTINEL = "";

    // Only allow versions like 3.21.2, 3.21.2.Final, 3.21.2-SNAPSHOT, 3.21.2.CR1
    private static final Pattern VALID_VERSION = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+([.\\-][A-Za-z0-9]+)*$");

    // Maven: <quarkus.platform.version>3.21.2</quarkus.platform.version>
    private static final Pattern MAVEN_PLATFORM_VERSION = Pattern.compile(
            "<quarkus\\.platform\\.version>([^<]+)</quarkus\\.platform\\.version>");

    // Maven fallback: <quarkus-plugin.version>3.21.2</quarkus-plugin.version>
    private static final Pattern MAVEN_PLUGIN_VERSION = Pattern.compile(
            "<quarkus-plugin\\.version>([^<]+)</quarkus-plugin\\.version>");

    // Gradle: quarkusPlatformVersion=3.21.2 (in gradle.properties)
    private static final Pattern GRADLE_PLATFORM_VERSION = Pattern.compile(
            "quarkusPlatformVersion\\s*=\\s*(.+)");

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
}
