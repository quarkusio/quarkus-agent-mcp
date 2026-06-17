package io.quarkus.agent.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Discovers {@code META-INF/quarkus-rag.sql} fragments from extension deployment JARs
 * in the local Maven repository and loads them into a pgvector database.
 * <p>
 * Supports incremental loading: when new extensions are added to a project,
 * only the new extension's SQL is loaded without reloading existing data.
 * Non-core extensions (Quarkiverse, third-party) are discovered by parsing
 * the project's {@code pom.xml}, following the same pattern as {@link SkillReader}.
 */
@ApplicationScoped
public class RagSqlLoader {

    private static final Logger LOG = Logger.getLogger(RagSqlLoader.class);

    private static final String RAG_SQL_PATH = "META-INF/quarkus-rag.sql";
    private static final String RAG_DATA_SQL_PATH = "META-INF/quarkus-rag-data.sql";
    private static final String DEPLOYMENT_SUFFIX = "-deployment";
    private static final String CORE_GROUP_ID = "io.quarkus";
    private static final String RAG_DOCUMENTS_TABLE = "rag_documents";

    private static final String CREATE_EXTENSION_DDL = "CREATE EXTENSION IF NOT EXISTS vector";
    private static final String CREATE_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS rag_documents (
                embedding_id UUID PRIMARY KEY,
                embedding vector(384),
                text TEXT,
                metadata JSONB
            )""";
    private static final String CREATE_INDEX_DDL = """
            CREATE INDEX IF NOT EXISTS idx_rag_embedding ON rag_documents
                USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)""";

    private static final String AGGREGATED_ARTIFACT_ID = "quarkus-documentation-core-rag";
    private static final String AGGREGATED_GROUP_PATH = "io/quarkus";

    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "metadata\\s*->>\\s*'source'\\s*=\\s*'([^']+)'");
    private static final Pattern ROW_SOURCE_PATTERN = Pattern.compile("\"source\"\\s*:\\s*\"([^\"]+)\"");

    record RagFragment(String source, String sql) {
    }

    private final Map<String, Set<String>> loadedSources = new ConcurrentHashMap<>();

    /**
     * Ensures RAG data is loaded for the given Quarkus version.
     * Discovers SQL fragments from core and non-core extension JARs,
     * filters out already-loaded sources, and loads only new data.
     * On first call for a version with a reused container, seeds tracking
     * from the database to avoid redundant loading.
     */
    public void ensureLoaded(String quarkusVersion, String projectDir,
            String host, int port, String database, String user, String password) {
        String versionKey = quarkusVersion != null ? quarkusVersion : "default";
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;

        ensureSchema(jdbcUrl, user, password);

        String resolvedVersion = quarkusVersion != null ? quarkusVersion : detectLatestInstalledVersion();
        if (resolvedVersion == null) {
            LOG.warn("Could not determine Quarkus version for RAG loading — no SQL fragments will be loaded");
            return;
        }

        List<RagFragment> allFragments = discoverSqlFragments(resolvedVersion, projectDir);
        if (allFragments.isEmpty()) {
            LOG.infof("No RAG SQL fragments found for Quarkus %s", resolvedVersion);
            return;
        }

        Set<String> alreadyLoaded = loadedSources.computeIfAbsent(versionKey,
                k -> ConcurrentHashMap.newKeySet());

        // On first call for this version, seed from the database (handles container reuse)
        if (alreadyLoaded.isEmpty()) {
            Set<String> existingSources = queryExistingSources(jdbcUrl, user, password);
            alreadyLoaded.addAll(existingSources);
        }

        List<RagFragment> newFragments = allFragments.stream()
                .filter(f -> !alreadyLoaded.contains(f.source()))
                .toList();

        if (newFragments.isEmpty()) {
            LOG.debugf("All %d RAG source(s) already loaded for %s", allFragments.size(), versionKey);
            return;
        }

        loadSql(jdbcUrl, user, password, newFragments, resolvedVersion);

        for (RagFragment f : newFragments) {
            alreadyLoaded.add(f.source());
        }
    }

    /**
     * Discovers RAG SQL fragments from extension deployment JARs in ~/.m2/repository.
     * Checks for the aggregated core artifact first, then scans individual core JARs
     * as a fallback. Always scans non-core extension JARs from the project's pom.xml.
     */
    List<RagFragment> discoverSqlFragments(String quarkusVersion, String projectDir) {
        Path m2Repo = Path.of(System.getProperty("user.home"), ".m2", "repository");

        List<RagFragment> fragments = new ArrayList<>();

        // 1. Core docs: aggregated artifact (preferred) or individual JARs (fallback)
        Path aggregatedJarPath = resolveAggregatedJarPath(quarkusVersion, m2Repo);
        RagFragment aggregated = readFragmentFromJar(aggregatedJarPath, "quarkus-documentation");
        if (aggregated != null) {
            fragments.add(aggregated);
            LOG.infof("Found aggregated RAG SQL artifact locally for Quarkus %s", quarkusVersion);
        } else {
            // Try downloading from Maven Central
            Path downloaded = downloadFromMavenCentral(quarkusVersion, aggregatedJarPath);
            if (downloaded != null) {
                aggregated = readFragmentFromJar(downloaded, "quarkus-documentation");
                if (aggregated != null) {
                    fragments.add(aggregated);
                    LOG.infof("Downloaded aggregated RAG SQL artifact for Quarkus %s", quarkusVersion);
                }
            }
        }

        // Fall back to individual core extension JARs if no aggregated artifact
        if (aggregated == null && Files.isDirectory(m2Repo)) {
            fragments.addAll(scanCoreExtensionJars(m2Repo, quarkusVersion));
        }

        // 2. Non-core extensions: always scan (Quarkiverse, third-party)
        fragments.addAll(scanNonCoreExtensionJars(m2Repo, projectDir));

        LOG.infof("Discovered %d RAG SQL fragment(s) for Quarkus %s", fragments.size(), quarkusVersion);
        return fragments;
    }

    private List<RagFragment> scanNonCoreExtensionJars(Path m2Repo, String projectDir) {
        if (projectDir == null) {
            return List.of();
        }

        List<DependencyResolver.Dependency> deps = DependencyResolver.resolve(projectDir);
        if (deps.isEmpty()) {
            return List.of();
        }

        List<RagFragment> fragments = new ArrayList<>();
        for (DependencyResolver.Dependency dep : deps) {
            if (CORE_GROUP_ID.equals(dep.groupId())) {
                continue;
            }
            String groupPath = dep.groupId().replace('.', '/');
            Path deploymentJar = m2Repo.resolve(groupPath)
                    .resolve(dep.artifactId() + DEPLOYMENT_SUFFIX)
                    .resolve(dep.version())
                    .resolve(dep.artifactId() + DEPLOYMENT_SUFFIX + "-" + dep.version() + ".jar");

            if (!Files.isRegularFile(deploymentJar)) {
                continue;
            }

            RagFragment fragment = readFragmentFromJar(deploymentJar, dep.artifactId());
            if (fragment != null) {
                fragments.add(fragment);
                LOG.debugf("Found RAG SQL in non-core extension %s", dep.artifactId());
            }
        }
        return fragments;
    }

    private Path resolveAggregatedJarPath(String version, Path m2Repo) {
        return m2Repo.resolve(AGGREGATED_GROUP_PATH)
                .resolve(AGGREGATED_ARTIFACT_ID)
                .resolve(version)
                .resolve(AGGREGATED_ARTIFACT_ID + "-" + version + ".jar");
    }

    private Path downloadFromMavenCentral(String version, Path targetPath) {
        if (version.endsWith("-SNAPSHOT")) {
            LOG.debugf("Skipping remote download for SNAPSHOT version %s", version);
            return null;
        }

        SkillReader.MavenRepoInfo repoInfo = SkillReader.resolveMavenRepoInfo(null);
        String artifactPath = "/" + AGGREGATED_GROUP_PATH + "/" + AGGREGATED_ARTIFACT_ID
                + "/" + version
                + "/" + AGGREGATED_ARTIFACT_ID + "-" + version + ".jar";
        String url = repoInfo.url() + artifactPath;

        LOG.infof("RAG SQL not found locally, downloading from %s...", url);

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET();

            SkillReader.addAuthHeader(requestBuilder, repoInfo, null);

            HttpRequest request = requestBuilder.build();

            HttpResponse<InputStream> response = HttpClientProvider.getHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                Files.createDirectories(targetPath.getParent());
                try (InputStream body = response.body()) {
                    Files.copy(body, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                LOG.infof("Downloaded RAG SQL artifact to %s", targetPath);
                return targetPath;
            } else {
                LOG.warnf("RAG SQL artifact not available at %s (HTTP %d) — documentation search will be limited",
                        url, response.statusCode());
                return null;
            }
        } catch (IOException | InterruptedException e) {
            LOG.warnf("Failed to download RAG SQL from %s: %s", url, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private List<RagFragment> scanCoreExtensionJars(Path m2Repo, String version) {
        Path quarkusDir = m2Repo.resolve("io/quarkus");
        if (!Files.isDirectory(quarkusDir)) {
            return List.of();
        }

        List<RagFragment> fragments = new ArrayList<>();
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

                RagFragment fragment = readFragmentFromJar(deploymentJar, artifactId);
                if (fragment != null) {
                    fragments.add(fragment);
                    LOG.debugf("Found RAG SQL in %s", deploymentArtifactId);
                }
            }
        } catch (IOException e) {
            LOG.debugf("Failed to scan extension JARs: %s", e.getMessage());
        }

        return fragments;
    }

    private RagFragment readFragmentFromJar(Path jarPath, String fallbackSource) {
        if (!Files.isRegularFile(jarPath)) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(RAG_DATA_SQL_PATH);
            if (entry == null) {
                entry = jar.getJarEntry(RAG_SQL_PATH);
            }
            if (entry == null) {
                return null;
            }

            String sql;
            try (InputStream is = jar.getInputStream(entry)) {
                sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            String source = extractSource(sql, fallbackSource);
            return new RagFragment(source, sql);
        } catch (IOException e) {
            LOG.debugf("Failed to read RAG SQL from %s: %s", jarPath, e.getMessage());
            return null;
        }
    }

    static String extractSource(String sql, String fallbackSource) {
        Matcher rowMatcher = ROW_SOURCE_PATTERN.matcher(sql);
        if (rowMatcher.find()) {
            return rowMatcher.group(1);
        }
        Matcher m = SOURCE_PATTERN.matcher(sql);
        if (m.find()) {
            return m.group(1);
        }
        return fallbackSource;
    }

    private void ensureSchema(String jdbcUrl, String user, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_EXTENSION_DDL);
            stmt.execute(CREATE_TABLE_DDL);
        } catch (SQLException e) {
            LOG.warnf("Failed to create RAG schema: %s", e.getMessage());
        }
    }

    private Set<String> queryExistingSources(String jdbcUrl, String user, String password) {
        Set<String> sources = new HashSet<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement stmt = conn.createStatement()) {
            // Table might not exist yet
            stmt.execute(CREATE_EXTENSION_DDL);
            stmt.execute(CREATE_TABLE_DDL);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT DISTINCT metadata->>'source' FROM " + RAG_DOCUMENTS_TABLE)) {
                while (rs.next()) {
                    String source = rs.getString(1);
                    if (source != null) {
                        sources.add(source);
                    }
                }
            }
            if (!sources.isEmpty()) {
                LOG.infof("Container already has RAG data for %d source(s)", sources.size());
            }
        } catch (SQLException e) {
            LOG.debugf("Failed to query existing RAG sources: %s", e.getMessage());
        }
        return sources;
    }

    private void loadSql(String jdbcUrl, String user, String password,
            List<RagFragment> fragments, String version) {
        LOG.infof("Loading %d RAG SQL fragment(s) for Quarkus %s...", fragments.size(), version);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_EXTENSION_DDL);
                stmt.execute(CREATE_TABLE_DDL);

                for (RagFragment fragment : fragments) {
                    for (String statement : splitSqlStatements(fragment.sql())) {
                        if (!statement.isBlank()) {
                            stmt.execute(statement);
                        }
                    }
                    LOG.debugf("Loaded RAG source: %s", fragment.source());
                }

                stmt.execute(CREATE_INDEX_DDL);
            }

            conn.commit();

            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + RAG_DOCUMENTS_TABLE)) {
                if (rs.next()) {
                    LOG.infof("RAG data loaded: %d total documents for Quarkus %s", rs.getLong(1), version);
                }
            }
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to load RAG SQL for Quarkus %s", version);
        }
    }

    /**
     * Splits a SQL string into individual statements.
     * Handles the fact that text values can contain semicolons.
     */
    static List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\n') {
                inLineComment = false;
                current.append(c);
                continue;
            }

            if (inLineComment) {
                continue;
            }

            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-' && !inSingleQuote) {
                inLineComment = true;
                continue;
            }

            if (c == '\'') {
                if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append('\'');
                    current.append('\'');
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
            }

            if (c == ';' && !inSingleQuote) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            statements.add(remaining);
        }

        return statements;
    }

    private String detectLatestInstalledVersion() {
        Path quarkusDir = Path.of(System.getProperty("user.home"), ".m2", "repository", "io", "quarkus", "quarkus-core");
        if (!Files.isDirectory(quarkusDir)) {
            return null;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarkusDir, Files::isDirectory)) {
            String latest = null;
            for (Path versionDir : stream) {
                String v = versionDir.getFileName().toString();
                if (!v.contains("SNAPSHOT") && (latest == null || v.compareTo(latest) > 0)) {
                    latest = v;
                }
            }
            return latest;
        } catch (IOException e) {
            return null;
        }
    }
}
