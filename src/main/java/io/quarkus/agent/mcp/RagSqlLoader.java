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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jboss.logging.Logger;

/**
 * Discovers {@code META-INF/quarkus-rag.sql} fragments from extension deployment JARs
 * in the local Maven repository and loads them into a pgvector database.
 * Follows the same JAR scanning pattern as {@link SkillReader}.
 */
@ApplicationScoped
public class RagSqlLoader {

    private static final Logger LOG = Logger.getLogger(RagSqlLoader.class);

    private static final String RAG_SQL_PATH = "META-INF/quarkus-rag.sql";
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

    private final Set<String> loadedVersions = ConcurrentHashMap.newKeySet();

    /**
     * Ensures RAG data is loaded for the given Quarkus version.
     * Discovers SQL fragments from extension JARs, creates the schema if needed,
     * and loads the data. Skips if already loaded for this version.
     */
    public void ensureLoaded(String quarkusVersion, String host, int port,
            String database, String user, String password) {
        String versionKey = quarkusVersion != null ? quarkusVersion : "default";
        if (loadedVersions.contains(versionKey)) {
            return;
        }

        String resolvedVersion = quarkusVersion != null ? quarkusVersion : detectLatestInstalledVersion();
        if (resolvedVersion == null) {
            LOG.warn("Could not determine Quarkus version for RAG loading — no SQL fragments will be loaded");
            loadedVersions.add(versionKey);
            return;
        }

        List<String> fragments = discoverSqlFragments(resolvedVersion);
        if (fragments.isEmpty()) {
            LOG.infof("No RAG SQL fragments found for Quarkus %s", resolvedVersion);
            loadedVersions.add(versionKey);
            return;
        }

        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        loadSql(jdbcUrl, user, password, fragments, resolvedVersion);
        loadedVersions.add(versionKey);
    }

    private static final String AGGREGATED_ARTIFACT_ID = "quarkus-documentation-core-rag";
    private static final String AGGREGATED_GROUP_PATH = "io/quarkus";

    /**
     * Discovers RAG SQL fragments from extension deployment JARs in ~/.m2/repository.
     * If the aggregated artifact is not found locally, downloads it from Maven Central.
     */
    List<String> discoverSqlFragments(String quarkusVersion) {
        Path m2Repo = Path.of(System.getProperty("user.home"), ".m2", "repository");

        List<String> fragments = new ArrayList<>();

        // 1. Check for aggregated artifact locally
        Path aggregatedJarPath = resolveAggregatedJarPath(quarkusVersion, m2Repo);
        if (Files.isRegularFile(aggregatedJarPath)) {
            String sql = readSqlFromJar(aggregatedJarPath);
            if (sql != null) {
                fragments.add(sql);
                LOG.infof("Found aggregated RAG SQL artifact locally for Quarkus %s", quarkusVersion);
                return fragments;
            }
        }

        // 2. Download aggregated artifact from Maven Central if not found locally
        if (fragments.isEmpty()) {
            Path downloaded = downloadFromMavenCentral(quarkusVersion, aggregatedJarPath);
            if (downloaded != null) {
                String sql = readSqlFromJar(downloaded);
                if (sql != null) {
                    fragments.add(sql);
                    LOG.infof("Downloaded aggregated RAG SQL artifact for Quarkus %s", quarkusVersion);
                    return fragments;
                }
            }
        }

        // 3. Scan individual core extension deployment JARs
        if (Files.isDirectory(m2Repo)) {
            fragments.addAll(scanCoreExtensionJars(m2Repo, quarkusVersion));
        }

        LOG.infof("Discovered %d RAG SQL fragment(s) for Quarkus %s", fragments.size(), quarkusVersion);
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

        String baseUrl = SkillReader.resolveMavenRepoBaseUrl(null);
        String artifactPath = "/" + AGGREGATED_GROUP_PATH + "/" + AGGREGATED_ARTIFACT_ID
                + "/" + version
                + "/" + AGGREGATED_ARTIFACT_ID + "-" + version + ".jar";
        String url = baseUrl + artifactPath;

        LOG.infof("RAG SQL not found locally, downloading from %s...", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

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

    private List<String> scanCoreExtensionJars(Path m2Repo, String version) {
        Path quarkusDir = m2Repo.resolve("io/quarkus");
        if (!Files.isDirectory(quarkusDir)) {
            return List.of();
        }

        List<String> fragments = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarkusDir,
                entry -> Files.isDirectory(entry)
                        && entry.getFileName().toString().startsWith("quarkus-")
                        && entry.getFileName().toString().endsWith(DEPLOYMENT_SUFFIX))) {
            for (Path extDir : stream) {
                String deploymentArtifactId = extDir.getFileName().toString();
                Path deploymentJar = extDir.resolve(version)
                        .resolve(deploymentArtifactId + "-" + version + ".jar");

                if (!Files.isRegularFile(deploymentJar)) {
                    continue;
                }

                String sql = readSqlFromJar(deploymentJar);
                if (sql != null) {
                    fragments.add(sql);
                    LOG.debugf("Found RAG SQL in %s", deploymentArtifactId);
                }
            }
        } catch (IOException e) {
            LOG.debugf("Failed to scan extension JARs: %s", e.getMessage());
        }

        return fragments;
    }

    private String readSqlFromJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Check data file first (aggregated artifact), then individual fragment
            JarEntry entry = jar.getJarEntry("META-INF/quarkus-rag-data.sql");
            if (entry == null) {
                entry = jar.getJarEntry(RAG_SQL_PATH);
            }
            if (entry == null) {
                return null;
            }

            try (InputStream is = jar.getInputStream(entry)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.debugf("Failed to read RAG SQL from %s: %s", jarPath, e.getMessage());
            return null;
        }
    }

    private void loadSql(String jdbcUrl, String user, String password,
            List<String> fragments, String version) {
        LOG.infof("Loading %d RAG SQL fragment(s) for Quarkus %s...", fragments.size(), version);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Create schema if needed
                stmt.execute(CREATE_EXTENSION_DDL);
                stmt.execute(CREATE_TABLE_DDL);

                // Check if data already exists (container might be reused)
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM " + RAG_DOCUMENTS_TABLE)) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        LOG.infof("RAG data already loaded (%d rows) — skipping", rs.getLong(1));
                        conn.rollback();
                        return;
                    }
                }

                // Load each fragment
                for (String fragment : fragments) {
                    for (String statement : splitSqlStatements(fragment)) {
                        if (!statement.isBlank()) {
                            stmt.execute(statement);
                        }
                    }
                }

                // Create index after bulk insert
                stmt.execute(CREATE_INDEX_DDL);
            }

            conn.commit();

            // Log final count
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + RAG_DOCUMENTS_TABLE)) {
                if (rs.next()) {
                    LOG.infof("RAG data loaded: %d documents for Quarkus %s", rs.getLong(1), version);
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

            if (c == '\'' && !isEscaped(sql, i)) {
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

    private static boolean isEscaped(String sql, int pos) {
        return pos > 0 && sql.charAt(pos - 1) == '\'';
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
