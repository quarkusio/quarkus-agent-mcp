package io.quarkus.agent.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Finds the Maven reactor a Quarkus module belongs to, so dev mode can be started from the
 * reactor root with {@code -pl <module> -am} and sibling modules are built from source.
 */
final class MavenReactor {

    private static final Logger LOG = Logger.getLogger(MavenReactor.class);

    static final String QUARKUS_MAVEN_PLUGIN = "quarkus-maven-plugin";
    static final String DEFAULT_PLUGIN_GROUP = "io.quarkus.platform";

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern VALID_GROUP_ID = Pattern.compile("^[A-Za-z0-9_.\\-]+$");
    private static final Pattern VALID_VERSION = Pattern.compile("^[0-9]+(\\.[0-9]+)*([.\\-][A-Za-z0-9]+)*$");

    private MavenReactor() {
    }

    /**
     * Walks up from {@code moduleDir} while the parent directory's pom.xml lists the current
     * directory as a module.
     *
     * @return the outermost aggregator directory, or empty if {@code moduleDir} is not a module of one
     */
    static Optional<Path> findRoot(Path moduleDir) {
        Path current = canonical(moduleDir);
        Path parent = current.getParent();
        while (parent != null && Files.isRegularFile(parent.resolve("pom.xml"))
                && listsModule(parent, current)) {
            current = parent;
            parent = current.getParent();
        }
        return current.equals(canonical(moduleDir)) ? Optional.empty() : Optional.of(current);
    }

    /**
     * The fully qualified dev goal, e.g. {@code io.quarkus.platform:quarkus-maven-plugin:3.21.2:dev}.
     * Run from the reactor root, the {@code quarkus:} prefix only resolves if the root pom declares
     * the plugin, so the plugin coordinates are read from the module and the poms above it.
     * Falls back to {@code quarkus:dev} if the version cannot be determined.
     */
    static String devGoal(Path moduleDir, Path root) {
        Path module = canonical(moduleDir);
        Path top = canonical(root);
        Map<String, String> properties = new HashMap<>();
        String groupId = null;
        String version = null;
        for (Path dir = module; dir != null; dir = dir.getParent()) {
            Document doc = parse(dir.resolve("pom.xml"));
            if (doc != null) {
                properties = merge(properties, readProperties(doc));
                Element plugin = findQuarkusPlugin(doc);
                if (plugin != null) {
                    if (groupId == null) {
                        groupId = childText(plugin, "groupId");
                    }
                    if (version == null) {
                        version = childText(plugin, "version");
                    }
                }
            }
            if (dir.equals(top)) {
                break;
            }
        }
        groupId = resolve(groupId, properties);
        version = resolve(version, properties);
        if (version == null || !VALID_VERSION.matcher(version).matches()) {
            version = QuarkusVersionDetector.detect(module.toString());
        }
        if (groupId == null || !VALID_GROUP_ID.matcher(groupId).matches()) {
            groupId = DEFAULT_PLUGIN_GROUP;
        }
        if (version == null) {
            LOG.debugf("Could not determine the %s version for %s, using the quarkus: prefix",
                    QUARKUS_MAVEN_PLUGIN, module);
            return "quarkus:dev";
        }
        return groupId + ":" + QUARKUS_MAVEN_PLUGIN + ":" + version + ":dev";
    }

    private static boolean listsModule(Path aggregator, Path module) {
        Document doc = parse(aggregator.resolve("pom.xml"));
        if (doc == null) {
            return false;
        }
        for (String name : moduleNames(doc)) {
            Path candidate = aggregator.resolve(name);
            if (candidate.getFileName() != null && candidate.getFileName().toString().endsWith(".xml")) {
                candidate = candidate.getParent();
            }
            if (candidate != null && canonical(candidate).equals(module)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> moduleNames(Document doc) {
        List<String> names = new ArrayList<>();
        // <module> (Maven 3) and <subproject> (Maven 4), including those declared in profiles
        for (String tag : new String[] { "module", "subproject" }) {
            NodeList nodes = doc.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                String text = nodes.item(i).getTextContent();
                if (text != null && !text.isBlank()) {
                    names.add(text.trim());
                }
            }
        }
        return names;
    }

    private static Element findQuarkusPlugin(Document doc) {
        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (QUARKUS_MAVEN_PLUGIN.equals(childText(plugin, "artifactId"))) {
                return plugin;
            }
        }
        return null;
    }

    private static Map<String, String> readProperties(Document doc) {
        Map<String, String> props = new HashMap<>();
        Element project = doc.getDocumentElement();
        for (Node n = project.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && "properties".equals(el.getTagName())) {
                for (Node p = el.getFirstChild(); p != null; p = p.getNextSibling()) {
                    if (p instanceof Element prop) {
                        props.put(prop.getTagName(), prop.getTextContent().trim());
                    }
                }
            }
        }
        return props;
    }

    /** Properties of a child pom override those of its parents. */
    private static Map<String, String> merge(Map<String, String> child, Map<String, String> parent) {
        Map<String, String> merged = new HashMap<>(parent);
        merged.putAll(child);
        return merged;
    }

    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String resolved = value;
        // A property may refer to another property, e.g. ${quarkus.platform.version}
        for (int depth = 0; depth < 5 && resolved.contains("${"); depth++) {
            Matcher m = PROPERTY_REF.matcher(resolved);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String replacement = properties.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : m.group(0)));
            }
            m.appendTail(sb);
            if (sb.toString().equals(resolved)) {
                break;
            }
            resolved = sb.toString();
        }
        return resolved.contains("${") ? null : resolved;
    }

    private static String childText(Element parent, String tagName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && tagName.equals(el.getTagName())) {
                return el.getTextContent().trim();
            }
        }
        return null;
    }

    private static Document parse(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pom.toFile());
        } catch (Exception e) {
            LOG.debugf("Failed to parse %s: %s", pom, e.getMessage());
            return null;
        }
    }

    private static Path canonical(Path path) {
        try {
            return new File(path.toString()).getCanonicalFile().toPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
