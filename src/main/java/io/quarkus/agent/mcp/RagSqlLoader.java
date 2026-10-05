package io.quarkus.agent.mcp;

import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Discovers {@code META-INF/quarkus-rag.sql} fragments from extension deployment JARs
 * in the local Maven repository and loads them into a pgvector database.
 * <p>
 * Supports incremental loading: when new extensions are added to a project,
 * only the new extension's SQL is loaded without reloading existing data. Each
 * loaded source is fingerprinted in {@code rag_sources}, so a fragment regenerated
 * upstream — an extension re-released with better embeddings, say — is reloaded
 * instead of being skipped as already present.
 * Non-core extensions (Quarkiverse, third-party) are discovered by parsing
 * the project's {@code pom.xml}, following the same pattern as {@link SkillReader}.
 */
@ApplicationScoped
public class RagSqlLoader {

    private static final Logger LOG = Logger.getLogger(RagSqlLoader.class);

    /**
     * Database system identifier -&gt; (source -&gt; fingerprint this server loaded into it). The
     * database's own {@code rag_sources} table is what decides whether something is loaded; this
     * only stops servers sharing a container from repeatedly overwriting each other's content.
     */
    private final Map<String, Map<String, String>> loadedHere = new ConcurrentHashMap<>();

    /** How long to wait for another server's load before giving up; below the 5-minute warm-up watchdog. */
    Duration loadLockTimeout = Duration.ofMinutes(4);

    @Inject
    WebClient webClient;

    /**
     * Hard budget for the per-dependency part of the non-core extension RAG scan. Individual
     * network/process fallbacks (HTTP download, {@code mvn dependency:get}) each carry their own
     * timeout, but on projects with many dependencies those add up to a long stall with no visible
     * progress. Once the budget is exceeded we stop attempting <em>new</em> network/process lookups
     * and finish the pass using only what is already available locally, so callers get a (possibly
     * partial) result in bounded time instead of hanging indefinitely.
     */
    @ConfigProperty(name = "agent-mcp.doc-search.non-core-scan-budget-millis", defaultValue = "120000")
    long nonCoreScanBudgetMillis;

    private static final String RAG_SQL_PATH = "META-INF/quarkus-rag.sql";
    private static final String RAG_DATA_SQL_PATH = "META-INF/quarkus-rag-data.sql";
    private static final String RAG_ARTIFACT_POINTER_PATH = "META-INF/quarkus-rag-artifact.properties";
    private static final String DEPLOYMENT_SUFFIX = "-deployment";
    private static final String CORE_GROUP_ID = "io.quarkus";
    private static final String RAG_DOCUMENTS_TABLE = "rag_documents";
    private static final String RAG_SOURCES_TABLE = "rag_sources";
    /** Advisory lock key serializing RAG loads across MCP servers sharing a container. */
    private static final long LOAD_LOCK_KEY = 0x71_6167_6e74_4d43L;

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
    /**
     * Records which fragment content is currently loaded for each source, so a source
     * whose fragment has been regenerated upstream is reloaded rather than skipped.
     */
    private static final String CREATE_SOURCES_DDL = """
            CREATE TABLE IF NOT EXISTS rag_sources (
                source TEXT PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                loaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )""";

    private static final String AGGREGATED_ARTIFACT_ID = "quarkus-documentation-core-rag";
    private static final String AGGREGATED_GROUP_PATH = "io/quarkus";

    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "metadata\\s*->>\\s*'source'\\s*=\\s*'([^']+)'");
    /**
     * A row's source: {@code source} must be the metadata object's first key, as the generator
     * emits it, as {@link #EXTENSION_FROM_SOURCE_PATTERN} already assumes, and as
     * {@link #appendSourceAndExtension} keeps it when rewriting a row. Matching a bare
     * {@code "source": "..."} anywhere would also hit JSON quoted inside a guide's own text -
     * the core docs artifact contains a platform-descriptor example that does exactly that -
     * and every source matched here is one a reload deletes rows for first, so a phantom that
     * ever collided with a real extension's source would silently wipe that extension's rows.
     */
    private static final Pattern ROW_SOURCE_PATTERN = Pattern.compile("\\{\"source\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EXTENSION_FROM_SOURCE_PATTERN = Pattern.compile(
            "'\\{\"source\":\"([^\"]+)\"");
    private static final Pattern EXTENSIONS_KEY_PATTERN = Pattern.compile("\"extensions\":\"([^\"]*)\"");
    private static final String VERSION_KEY = ",\"version\":";
    private static final String QUARKUS_VERSION_KEY = ",\"quarkus_version\":";

    /**
     * @param sql            in-memory SQL content, or {@code null} when {@code streamJarPath} is set
     * @param streamJarPath  when non-null, the fragment's SQL must be streamed line-by-line from the
     *                       {@code META-INF/quarkus-rag(-data).sql} entry of this jar rather than read
     *                       into memory (used for the large aggregated core-docs artifact)
     */
    record RagFragment(String source, String sql, Path streamJarPath) {
        RagFragment(String source, String sql) {
            this(source, sql, null);
        }
    }

    record RagArtifactPointer(String groupId, String artifactId) {
    }

    /** The jar's RAG SQL entry under either of the two names used, or null if it has neither. */
    private static JarEntry ragSqlEntry(JarFile jar) {
        JarEntry entry = jar.getJarEntry(RAG_DATA_SQL_PATH);
        return entry != null ? entry : jar.getJarEntry(RAG_SQL_PATH);
    }

    /**
     * Ensures RAG data is loaded for the given Quarkus version.
     * Discovers SQL fragments from core and non-core extension JARs, and loads
     * those that are new or whose content has changed since they were last loaded.
     * <p>
     * What is loaded is always read from the database's {@code rag_sources} table, never kept in
     * memory: the container can be shared with other MCP servers or replaced underneath this one,
     * and only the database knows what it actually holds. Schema creation and loading run under
     * one advisory lock, so servers sharing a container take turns instead of deadlocking.
     */
    public void ensureLoaded(String quarkusVersion, String projectDir,
            String host, int port, String database, String user, String password) {
        String versionKey = quarkusVersion != null ? quarkusVersion : "default";
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        LOG.infof("RAG ensureLoaded: starting for version=%s, projectDir=%s", versionKey, projectDir);

        // Discovery reads jars and may resolve Maven artifacts, so it runs before taking the lock
        String resolvedVersion = quarkusVersion != null ? quarkusVersion : detectLatestInstalledVersion();
        List<RagFragment> allFragments = List.of();
        if (resolvedVersion == null) {
            LOG.warn("Could not determine Quarkus version for RAG loading — no SQL fragments will be loaded");
        } else {
            long discoverStart = System.currentTimeMillis();
            allFragments = discoverSqlFragments(resolvedVersion, projectDir);
            LOG.infof("RAG ensureLoaded: discovered %d fragment(s) for %s in %d ms", allFragments.size(),
                    resolvedVersion, System.currentTimeMillis() - discoverStart);
            if (allFragments.isEmpty()) {
                LOG.infof("No RAG SQL fragments found for Quarkus %s", resolvedVersion);
            }
        }

        List<RagFragment> fragments = allFragments;
        withLoadLock(jdbcUrl, user, password, () -> {
            ensureSchema(jdbcUrl, user, password);
            if (!fragments.isEmpty()) {
                loadStaleFragments(versionKey, resolvedVersion, fragments, jdbcUrl, user, password);
            }
        });
    }

    void loadStaleFragments(String versionKey, String resolvedVersion, List<RagFragment> allFragments,
            String jdbcUrl, String user, String password) {
        Map<String, String> inDatabase = queryLoadedFingerprints(jdbcUrl, user, password);
        Map<String, String> loadedByUs = loadedHere.computeIfAbsent(databaseId(jdbcUrl, user, password),
                k -> new ConcurrentHashMap<>());

        List<StaleFragment> staleFragments = new ArrayList<>();
        for (RagFragment fragment : allFragments) {
            String fingerprint = fingerprint(resolvedVersion, fragment);
            if (fingerprint != null && needsLoading(fingerprint, inDatabase.get(fragment.source()),
                    loadedByUs.get(fragment.source()))) {
                staleFragments.add(new StaleFragment(fragment, fingerprint));
            }
        }

        if (staleFragments.isEmpty()) {
            LOG.debugf("All %d RAG source(s) already loaded and up to date for %s",
                    allFragments.size(), versionKey);
            return;
        }

        if (loadSql(jdbcUrl, user, password, staleFragments, resolvedVersion)) {
            for (StaleFragment stale : staleFragments) {
                loadedByUs.put(stale.fragment().source(), stale.fingerprint());
            }
        }
    }

    /**
     * Whether to load a fragment, given what the database holds for its source and what this
     * server last loaded into this same database. Missing sources are always loaded, as is content
     * that differs from what is there. The exception: servers sharing a container can want
     * different content for the same source (different versions of an extension in their
     * projects), and once this server has loaded its content it does not overwrite another
     * server's again, or the two would keep replacing each other's rows.
     */
    static boolean needsLoading(String wanted, String inDatabase, String loadedByUs) {
        if (inDatabase == null) {
            return true;
        }
        return !wanted.equals(inDatabase) && !wanted.equals(loadedByUs);
    }

    /**
     * Identifies the database itself rather than its address: Postgres's system identifier is
     * generated when the data directory is created, so a replaced container gets a new one even if
     * it ends up on the same host port. Falls back to the address if it cannot be read.
     */
    private static String databaseId(String jdbcUrl, String user, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT system_identifier FROM pg_control_system()")) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.debugf("Could not read the database's system identifier: %s", e.getMessage());
        }
        return jdbcUrl;
    }

    /** A fragment that needs (re)loading, and the fingerprint to record once it is loaded. */
    record StaleFragment(RagFragment fragment, String fingerprint) {
    }

    /**
     * Identifies the content currently loaded for a fragment, so one regenerated upstream is
     * reloaded rather than skipped. The Quarkus version is part of it because that is injected
     * into row metadata at load time: the same fragment under a different version really is
     * different content in the database.
     * <p>
     * A streamed fragment is fingerprinted from its jar entry's size and CRC-32, which the zip
     * central directory already carries, rather than by reading it. This runs on every start,
     * and the aggregated core artifact is tens of MB, so hashing the content would mean
     * decompressing all of it just to conclude that nothing has changed.
     *
     * @return null if a streamed fragment's jar could not be read, in which case it is left
     *         alone rather than reloaded on a guess
     */
    private String fingerprint(String quarkusVersion, RagFragment fragment) {
        if (fragment.streamJarPath() == null) {
            return fingerprint(quarkusVersion, fragment.sql());
        }
        try (JarFile jar = new JarFile(fragment.streamJarPath().toFile())) {
            JarEntry entry = ragSqlEntry(jar);
            if (entry == null || entry.getSize() < 0 || entry.getCrc() < 0) {
                return null;
            }
            return fingerprint(quarkusVersion, entry.getSize() + ":" + entry.getCrc());
        } catch (IOException e) {
            LOG.warnf("Failed to fingerprint streamed RAG fragment %s: %s",
                    fragment.streamJarPath(), e.getMessage());
            return null;
        }
    }

    static String fingerprint(String quarkusVersion, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(quarkusVersion.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /**
     * Every source a fragment writes rows for, which is what a reload has to clear first.
     * A streamed fragment is read line by line rather than materialized, so the aggregated
     * core artifact never becomes a String; that read only happens when it is actually being
     * reloaded, alongside the far more expensive execution of its statements.
     */
    private Set<String> fragmentSources(RagFragment fragment) throws SQLException {
        if (fragment.streamJarPath() == null) {
            return extractSources(fragment.sql(), fragment.source());
        }
        try (JarFile jar = new JarFile(fragment.streamJarPath().toFile())) {
            JarEntry entry = ragSqlEntry(jar);
            if (entry == null) {
                return Set.of();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8), 1 << 16)) {
                return streamSources(reader, fragment.source());
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read sources from " + fragment.streamJarPath(), e);
        }
    }

    /** Streaming equivalent of {@link #extractSources}. */
    static Set<String> streamSources(BufferedReader reader, String fallbackSource) throws IOException {
        Set<String> sources = new LinkedHashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            collectSources(line, sources);
        }
        if (sources.isEmpty() && fallbackSource != null) {
            sources.add(fallbackSource);
        }
        return sources;
    }

    /**
     * Returns every {@code source} the fragment writes rows for. An aggregated fragment
     * such as the core docs artifact carries hundreds, and its own DELETE statement only
     * covers the name it was generated under, so reloading it needs the full set.
     */
    static Set<String> extractSources(String sql, String fallbackSource) {
        Set<String> sources = new LinkedHashSet<>();
        collectSources(sql, sources);
        if (sources.isEmpty() && fallbackSource != null) {
            sources.add(fallbackSource);
        }
        return sources;
    }

    /**
     * Adds every source named in a chunk of SQL to {@code sources}. Shared with the streaming
     * scan, which calls it a line at a time: neither pattern can match across a line break, so
     * the two arrive at the same set.
     */
    private static void collectSources(CharSequence sql, Set<String> sources) {
        Matcher rowMatcher = ROW_SOURCE_PATTERN.matcher(sql);
        while (rowMatcher.find()) {
            sources.add(rowMatcher.group(1));
        }
        Matcher deleteMatcher = SOURCE_PATTERN.matcher(sql);
        while (deleteMatcher.find()) {
            sources.add(deleteMatcher.group(1));
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
        // The aggregated artifact can be tens of MB (e.g. ~47MB for the current Quarkus
        // documentation artifact, one JSON/vector row per line). We only "peek" the source
        // name here (cheap: reads a handful of lines) and defer the actual content to a
        // streaming fragment that is read/transformed/executed line-by-line in loadSql(),
        // instead of ever materializing the whole file as one in-memory String.
        Path aggregatedJarPath = resolveAggregatedJarPath(quarkusVersion, m2Repo);
        RagFragment aggregated = peekStreamedFragment(aggregatedJarPath, "quarkus-documentation");
        if (aggregated != null) {
            fragments.add(aggregated);
            LOG.infof("Found aggregated RAG SQL artifact locally for Quarkus %s", quarkusVersion);
        } else {
            // Try downloading from Maven Central
            Path downloaded = downloadFromMavenCentral(quarkusVersion, aggregatedJarPath);
            if (downloaded != null) {
                aggregated = peekStreamedFragment(downloaded, "quarkus-documentation");
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
        fragments.addAll(scanNonCoreExtensionJars(m2Repo, projectDir, quarkusVersion));

        LOG.infof("Discovered %d RAG SQL fragment(s) for Quarkus %s", fragments.size(), quarkusVersion);
        return fragments;
    }

    /**
     * Resolves the dependencies the non-core scan walks.
     *
     * <p>Unlike the skills subsystem, which exposes the same choice as
     * {@code agent-mcp.skills.include-transitive} and defaults it to {@code false}, doc discovery
     * always resolves transitively. The two paths have different shapes: {@code quarkus_skills} is
     * read on every skills call in the agent loop, whereas this scan runs behind a pgvector
     * container start and a 60s throttle, so the one-off {@code mvn dependency:list} fork is noise
     * next to work already being done. Making it opt-in would also leave the common case broken,
     * because an extension family that ships its RAG artifact pointer in a single module is only
     * ever reachable transitively from an application that declares a sibling module.
     *
     * <p>Package-private, and taking the flag explicitly, so a test can drive the scan without
     * forking Maven and still fail if the call site stops asking for transitive resolution.
     */
    List<DependencyResolver.Dependency> resolveDependencies(String projectDir, boolean includeTransitive) {
        return DependencyResolver.resolve(projectDir, includeTransitive);
    }

    List<RagFragment> scanNonCoreExtensionJars(Path m2Repo, String projectDir, String quarkusVersion) {
        if (projectDir == null) {
            return List.of();
        }

        long resolveStart = System.currentTimeMillis();
        List<DependencyResolver.Dependency> deps = resolveDependencies(projectDir, true);
        LOG.infof("RAG scan: resolved %d dependencies for %s in %d ms", deps.size(), projectDir,
                System.currentTimeMillis() - resolveStart);
        if (deps.isEmpty()) {
            return List.of();
        }

        // The budget clock starts here, after dependency resolution, not at method entry.
        // Resolution shells out to Maven whenever the pom leaves versions to a BOM -- the norm
        // for a Quarkus project -- and carries its own 180s timeout, which on its own exceeds
        // the default budget. Timing from method entry would therefore skip every lookup below
        // before the first iteration, on exactly the slow-network setups the budget is for.
        long scanStart = System.currentTimeMillis();

        List<RagFragment> fragments = new ArrayList<>();
        // Several modules of one extension family can carry a pointer to the same RAG artifact.
        // Resolving it once per pointing module would load the same corpus repeatedly, under a
        // different extension name each time. Transitive resolution makes that likely enough to
        // guard against: a family's modules are now all in `deps`, not just the declared one.
        Set<String> seenPointers = new HashSet<>();
        int index = 0;
        int skippedDueToBudget = 0;
        for (DependencyResolver.Dependency dep : deps) {
            index++;
            if (CORE_GROUP_ID.equals(dep.groupId())) {
                continue;
            }
            long elapsedSoFar = System.currentTimeMillis() - scanStart;
            boolean budgetExceeded = elapsedSoFar > nonCoreScanBudgetMillis;

            String groupPath = dep.groupId().replace('.', '/');
            Path deploymentJar = m2Repo.resolve(groupPath)
                    .resolve(dep.artifactId() + DEPLOYMENT_SUFFIX)
                    .resolve(dep.version())
                    .resolve(dep.artifactId() + DEPLOYMENT_SUFFIX + "-" + dep.version() + ".jar");

            if (!Files.isRegularFile(deploymentJar)) {
                LOG.debugf("RAG scan [%d/%d] %s:%s — no deployment jar at %s, skipping",
                        index, deps.size(), dep.groupId(), dep.artifactId(), deploymentJar);
                continue;
            }

            // Check for a pointer to a separate RAG artifact
            RagArtifactPointer pointer = readRagArtifactPointer(deploymentJar);
            if (pointer != null) {
                if (budgetExceeded) {
                    skippedDueToBudget++;
                    LOG.warnf(
                            "RAG scan [%d/%d] %s:%s — skipping remote RAG artifact lookup (%s:%s), "
                                    + "%d ms scan budget exceeded (elapsed %d ms)",
                            index, deps.size(), dep.groupId(), dep.artifactId(),
                            pointer.groupId(), pointer.artifactId(), nonCoreScanBudgetMillis, elapsedSoFar);
                    continue;
                }
                String pointerKey = pointer.groupId() + ":" + pointer.artifactId();
                if (!seenPointers.add(pointerKey)) {
                    // Guard the external lookup only, then fall through: this module may still
                    // ship RAG SQL of its own, and that is a different corpus to the shared
                    // artifact an earlier module already brought in.
                    LOG.debugf("RAG scan [%d/%d] %s:%s — RAG artifact %s already resolved via an "
                            + "earlier dependency, not resolving it again",
                            index, deps.size(), dep.groupId(), dep.artifactId(), pointerKey);
                } else {
                    long depStart = System.currentTimeMillis();
                    LOG.infof("RAG scan [%d/%d] %s:%s — resolving external RAG artifact %s:%s:%s...",
                            index, deps.size(), dep.groupId(), dep.artifactId(),
                            pointer.groupId(), pointer.artifactId(), dep.version());
                    RagFragment fragment = resolveExternalRagArtifact(
                            pointer, dep.version(), m2Repo, projectDir);
                    long depElapsed = System.currentTimeMillis() - depStart;
                    if (fragment != null) {
                        String guideUrl = readGuideUrl(m2Repo, dep);
                        fragments.add(injectExtensionMetadata(fragment, dep.groupId(), dep.artifactId(), quarkusVersion, guideUrl));
                        LOG.infof("RAG scan [%d/%d] %s:%s — found RAG SQL via external artifact %s:%s:%s (%d ms)",
                                index, deps.size(), dep.groupId(), dep.artifactId(),
                                pointer.groupId(), pointer.artifactId(), dep.version(), depElapsed);
                        continue;
                    }
                    LOG.infof(
                            "RAG scan [%d/%d] %s:%s — external RAG artifact %s:%s:%s unavailable (%d ms), "
                                    + "falling back to deployment jar contents",
                            index, deps.size(), dep.groupId(), dep.artifactId(),
                            pointer.groupId(), pointer.artifactId(), dep.version(), depElapsed);
                }
            }

            // Fallback: read RAG SQL directly from the deployment JAR
            RagFragment fragment = readFragmentFromJar(deploymentJar, dep.artifactId());
            if (fragment != null) {
                String guideUrl = readGuideUrl(m2Repo, dep);
                fragments.add(injectExtensionMetadata(fragment, dep.groupId(), dep.artifactId(), quarkusVersion, guideUrl));
                LOG.debugf("Found RAG SQL in non-core extension %s", dep.artifactId());
            }
        }
        long totalElapsed = System.currentTimeMillis() - scanStart;
        LOG.infof("RAG scan: finished %d dependencies in %d ms (%d fragment(s) found, %d skipped due to budget)",
                deps.size(), totalElapsed, fragments.size(), skippedDueToBudget);
        return fragments;
    }

    private RagArtifactPointer readRagArtifactPointer(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(RAG_ARTIFACT_POINTER_PATH);
            if (entry == null) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream is = jar.getInputStream(entry)) {
                props.load(is);
            }
            String groupId = props.getProperty("groupId");
            String artifactId = props.getProperty("artifactId");
            if (groupId == null || artifactId == null) {
                LOG.warnf("Invalid RAG artifact pointer in %s: groupId=%s, artifactId=%s",
                        jarPath, groupId, artifactId);
                return null;
            }
            return new RagArtifactPointer(groupId.trim(), artifactId.trim());
        } catch (IOException e) {
            LOG.debugf("Failed to read RAG artifact pointer from %s: %s",
                    jarPath, e.getMessage());
            return null;
        }
    }

    private RagFragment resolveExternalRagArtifact(
            RagArtifactPointer pointer, String version,
            Path m2Repo, String projectDir) {
        String groupPath = pointer.groupId().replace('.', '/');
        Path ragJarPath = m2Repo.resolve(groupPath)
                .resolve(pointer.artifactId())
                .resolve(version)
                .resolve(pointer.artifactId() + "-" + version + ".jar");

        // Try local first
        RagFragment fragment = readFragmentFromJar(ragJarPath, pointer.artifactId());
        if (fragment != null) {
            return fragment;
        }

        // Try fast HTTP download (works for non-SNAPSHOT releases)
        Path downloaded = downloadArtifact(groupPath, pointer.artifactId(),
                version, ragJarPath, projectDir);
        if (downloaded != null) {
            return readFragmentFromJar(downloaded, pointer.artifactId());
        }

        // Fallback: fetch via Maven (handles SNAPSHOTs and custom repos)
        if (fetchArtifactViaMaven(pointer, version, projectDir)) {
            fragment = readFragmentFromJar(ragJarPath, pointer.artifactId());
            if (fragment != null) {
                return fragment;
            }
        }

        return null;
    }

    private boolean fetchArtifactViaMaven(RagArtifactPointer pointer, String version, String projectDir) {
        if (projectDir == null) {
            return false;
        }
        File dir = new File(projectDir);
        if (!dir.isDirectory()) {
            return false;
        }
        String mvnCmd = ProcessUtils.resolveMavenCommand(dir);
        String artifact = pointer.groupId() + ":" + pointer.artifactId() + ":" + version;
        LOG.infof("RAG artifact not found locally, fetching %s via Maven (cmd=%s, dir=%s)...",
                artifact, mvnCmd, dir);

        ProcessBuilder pb = new ProcessBuilder(
                mvnCmd, "dependency:get",
                "-Dartifact=" + artifact,
                "-Dtransitive=false",
                "-q")
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            try {
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    LOG.warnf("Maven dependency:get timed out after %d ms for %s — documentation search may be limited",
                            System.currentTimeMillis() - start, artifact);
                    return false;
                }
                long elapsed = System.currentTimeMillis() - start;
                if (process.exitValue() == 0) {
                    LOG.infof("Successfully fetched RAG artifact %s via Maven (%d ms)", artifact, elapsed);
                    return true;
                }
                LOG.warnf(
                        "Maven dependency:get failed for %s (exit code %d, %d ms) — RAG data for this extension will be unavailable",
                        artifact, process.exitValue(), elapsed);
                return false;
            } finally {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            LOG.warnf("Failed to start Maven for dependency:get (%s) after %d ms: %s",
                    artifact, System.currentTimeMillis() - start, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugf("Maven dependency:get interrupted for %s after %d ms",
                    artifact, System.currentTimeMillis() - start);
            return false;
        }
    }

    /**
     * Fixes metadata in non-core extension SQL fragments. The upstream plugin generates
     * metadata assuming core Quarkus conventions; this method corrects it at load time:
     * <ul>
     *   <li>{@code source} — replaced with the correct runtime artifact ID, unless the row comes
     *       from a guide that declares its own extension (see {@link #declaresOwnSource})</li>
     *   <li>{@code quarkus_version} — renamed to {@code extension_version}; actual Quarkus version injected</li>
     *   <li>{@code url} — the plugin's default quarkus.io URL replaced with the guide URL from
     *       {@code quarkus-extension.yaml}, or removed when there is none</li>
     *   <li>{@code extension} — added, matching the row's source</li>
     * </ul>
     */
    static RagFragment injectExtensionMetadata(RagFragment fragment, String groupId, String extensionName,
            String quarkusVersion, String guideUrl) {
        String sql = fragment.sql();

        // Fix source in DELETE statement
        sql = SOURCE_PATTERN.matcher(sql).replaceAll(
                Matcher.quoteReplacement("metadata->>'source' = '" + extensionName + "'"));

        sql = fixRowSources(sql, groupId, extensionName);

        // Rename version key to extension_version and inject correct quarkus_version.
        // Handles both old plugin format ("quarkus_version":) and new format (,"version":).
        if (quarkusVersion != null) {
            sql = sql.replace("\"quarkus_version\":", "\"extension_version\":");
            sql = sql.replace(",\"version\":", ",\"extension_version\":");
            sql = sql.replace("\"extension_version\":",
                    "\"quarkus_version\":\"" + quarkusVersion + "\",\"extension_version\":");
        }

        // Fix URL: replace wrong quarkus.io URLs with the guide URL from extension metadata, or remove them
        if (guideUrl != null) {
            sql = sql.replaceAll("\"url\":\"https://quarkus\\.io/guides/[^\"]*\"",
                    Matcher.quoteReplacement("\"url\":\"" + guideUrl + "\""));
        } else {
            sql = sql.replaceAll(",\"url\":\"https://quarkus\\.io/guides/[^\"]*\"", "");
        }

        return new RagFragment(extensionName, sql);
    }

    /**
     * Sets each row's {@code source}, and the {@code extension} key placed after it, to
     * {@code extensionName}, except for rows that {@linkplain #declaresOwnSource declare their own}.
     * {@code source} has to stay the metadata object's first key so that {@link #ROW_SOURCE_PATTERN}
     * still finds it: a row keeping its own source is only deleted on reload if the pattern sees it.
     */
    private static String fixRowSources(String sql, String groupId, String extensionName) {
        Matcher matcher = EXTENSION_FROM_SOURCE_PATTERN.matcher(sql);
        StringBuilder result = new StringBuilder(sql.length() + 64);
        int lastEnd = 0;
        while (matcher.find()) {
            String rowSource = matcher.group(1);
            String source = declaresOwnSource(sql, matcher.end(), groupId, rowSource) ? rowSource : extensionName;
            result.append(sql, lastEnd, matcher.start());
            appendSourceAndExtension(result, source);
            lastEnd = matcher.end();
        }
        result.append(sql, lastEnd, sql.length());
        return result.toString();
    }

    /**
     * Opens a row's metadata object with {@code source} followed by the {@code extension} key the
     * search filter matches on. {@code source} comes first because {@link #ROW_SOURCE_PATTERN} is
     * anchored on the opening brace, and the sources it finds are the ones a reload deletes before
     * re-running the fragment's INSERTs - a row whose source is not found keeps its old copy, and
     * the INSERT then collides on the baked-in {@code embedding_id} primary key.
     */
    private static void appendSourceAndExtension(StringBuilder result, String source) {
        result.append("'{\"source\":\"").append(source)
                .append("\",\"extension\":\"").append(source).append('"');
    }

    /**
     * Whether the row starting at {@code from} was generated from a guide whose {@code :extensions:}
     * header names, first, {@code rowSource} within {@code groupId}. The plugin only writes the
     * {@code extensions} key for such guides, so the source is the guide's own rather than a
     * fallback guessed from its file name. The group must match because every source a fragment
     * carries is deleted before it is reloaded: a guide naming another group's extension, such as
     * a core one, would otherwise wipe that extension's rows.
     */
    private static boolean declaresOwnSource(String sql, int from, String groupId, String rowSource) {
        int end = sql.indexOf("}'::jsonb", from);
        if (groupId == null || end < 0) {
            return false;
        }
        Matcher matcher = EXTENSIONS_KEY_PATTERN.matcher(sql).region(from, end);
        if (!matcher.find()) {
            return false;
        }
        String firstExtension = matcher.group(1).split(",")[0].trim();
        return firstExtension.equals(groupId + ":" + rowSource);
    }

    private String readGuideUrl(Path m2Repo, DependencyResolver.Dependency dep) {
        String groupPath = dep.groupId().replace('.', '/');
        Path runtimeJar = m2Repo.resolve(groupPath)
                .resolve(dep.artifactId())
                .resolve(dep.version())
                .resolve(dep.artifactId() + "-" + dep.version() + ".jar");
        if (!Files.isRegularFile(runtimeJar)) {
            return null;
        }
        try (JarFile jar = new JarFile(runtimeJar.toFile())) {
            SkillReader.ExtensionMetadata meta = SkillReader.readExtensionMetadata(jar);
            return meta != null ? meta.guide : null;
        } catch (IOException e) {
            LOG.debugf("Failed to read guide URL from %s: %s", runtimeJar, e.getMessage());
            return null;
        }
    }

    private RagFragment injectExtensionFromSource(RagFragment fragment) {
        // Single-pass transform instead of two chained String.replaceAll()/replace() calls.
        // The aggregated core-docs SQL fragment can be tens of MB (e.g. ~47MB for the current
        // Quarkus documentation artifact); doing two full-string regex/replace passes each
        // materializes a fresh copy of the whole string, which can spike memory well past a
        // constrained heap (observed to OOM with -Xmx512m). Here we scan once and copy each
        // "between match" chunk straight into a pre-sized StringBuilder, applying the literal
        // ",\"version\":" -> ",\"quarkus_version\":" fix-up per chunk instead of on the whole
        // string, so peak memory stays close to ~1x the input size rather than ~3-4x.
        String sql = fragment.sql();
        Matcher matcher = EXTENSION_FROM_SOURCE_PATTERN.matcher(sql);
        StringBuilder result = new StringBuilder(sql.length() + 64);
        int lastEnd = 0;
        while (matcher.find()) {
            appendWithVersionKeyFix(result, sql, lastEnd, matcher.start());
            appendSourceAndExtension(result, matcher.group(1));
            lastEnd = matcher.end();
        }
        appendWithVersionKeyFix(result, sql, lastEnd, sql.length());
        return new RagFragment(fragment.source(), result.toString());
    }

    /**
     * Appends sql[from, to) to result, rewriting the generic "version" JSON key to
     * "quarkus_version" for core extensions, without allocating an intermediate copy
     * of the (potentially very large) full string.
     */
    private static void appendWithVersionKeyFix(StringBuilder result, String sql, int from, int to) {
        int searchFrom = from;
        int idx;
        while ((idx = sql.indexOf(VERSION_KEY, searchFrom)) != -1 && idx < to) {
            result.append(sql, searchFrom, idx).append(QUARKUS_VERSION_KEY);
            searchFrom = idx + VERSION_KEY.length();
        }
        result.append(sql, searchFrom, to);
    }

    private Path resolveAggregatedJarPath(String version, Path m2Repo) {
        return m2Repo.resolve(AGGREGATED_GROUP_PATH)
                .resolve(AGGREGATED_ARTIFACT_ID)
                .resolve(version)
                .resolve(AGGREGATED_ARTIFACT_ID + "-" + version + ".jar");
    }

    private Path downloadFromMavenCentral(String version, Path targetPath) {
        return downloadArtifact(AGGREGATED_GROUP_PATH, AGGREGATED_ARTIFACT_ID,
                version, targetPath, null);
    }

    private Path downloadArtifact(String groupPath, String artifactId,
            String version, Path targetPath, String projectDir) {
        if (version.endsWith("-SNAPSHOT")) {
            LOG.debugf("Skipping remote download for SNAPSHOT version %s", version);
            return null;
        }

        SkillReader.MavenRepoInfo repoInfo = SkillReader.resolveMavenRepoInfo(projectDir);
        String artifactUrlPath = "/" + groupPath + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + ".jar";
        String url = repoInfo.url() + artifactUrlPath;

        LOG.infof("RAG SQL not found locally, downloading from %s...", url);

        long start = System.currentTimeMillis();
        try {
            var request = webClient.getAbs(url).timeout(60_000);
            SkillReader.addAuthHeader(request, repoInfo, projectDir);

            var response = request.send().await().atMost(Duration.ofSeconds(65));
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                Files.createDirectories(targetPath.getParent());
                Files.write(targetPath, response.body().getBytes(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                LOG.infof("Downloaded RAG SQL artifact to %s (%d ms)", targetPath, elapsed);
                return targetPath;
            } else {
                LOG.warnf("RAG SQL artifact not available at %s (HTTP %d, %d ms) — documentation search will be limited",
                        url, response.statusCode(), elapsed);
                return null;
            }
        } catch (IOException | RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("Failed to download RAG SQL from %s after %d ms: %s", url, elapsed, e.getMessage());
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
                    fragments.add(injectExtensionFromSource(fragment));
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
            JarEntry entry = ragSqlEntry(jar);
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

    /**
     * Like {@link #readFragmentFromJar}, but never materializes the (potentially very large)
     * SQL content in memory: it only reads far enough to determine the fragment's {@code source}
     * name (a handful of lines, in practice), and returns a fragment that streams its actual
     * content later, directly from the jar, in {@link #streamFragmentIntoDb}.
     */
    private RagFragment peekStreamedFragment(Path jarPath, String fallbackSource) {
        if (!Files.isRegularFile(jarPath)) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = ragSqlEntry(jar);
            if (entry == null) {
                return null;
            }
            String source;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                source = peekSource(reader, fallbackSource);
            }
            return new RagFragment(source, null, jarPath);
        } catch (IOException e) {
            LOG.debugf("Failed to peek RAG SQL source from %s: %s", jarPath, e.getMessage());
            return null;
        }
    }

    /**
     * Streaming equivalent of {@link #extractSource(String, String)}, and it must agree with it:
     * the fragment's source is how {@code ensureLoaded} decides whether the fragment is already
     * in the database, and that check compares against {@code metadata->>'source'} values of rows
     * actually present. A row source therefore wins over the name in the leading DELETE, which for
     * the aggregated core artifact is {@code quarkus-documentation} and matches no row at all.
     * Returning the DELETE name would make the fragment look permanently absent and re-execute it
     * on every restart against a reused container, colliding on the baked-in embedding_id keys.
     * <p>
     * Reading stops at the first row source, so in practice this consumes a handful of lines.
     */
    static String peekSource(BufferedReader reader, String fallbackSource) throws IOException {
        String deleteSource = null;
        String line;
        while ((line = reader.readLine()) != null) {
            Matcher rowMatcher = ROW_SOURCE_PATTERN.matcher(line);
            if (rowMatcher.find()) {
                return rowMatcher.group(1);
            }
            if (deleteSource == null) {
                Matcher deleteMatcher = SOURCE_PATTERN.matcher(line);
                if (deleteMatcher.find()) {
                    deleteSource = deleteMatcher.group(1);
                }
            }
        }
        return deleteSource != null ? deleteSource : fallbackSource;
    }

    @FunctionalInterface
    interface SqlStatementSink {
        void accept(String statement) throws SQLException;
    }

    /**
     * Reads a {@code fragment.streamJarPath()}'s {@code META-INF/quarkus-rag(-data).sql} entry
     * and executes each statement directly against the database as it is parsed, transforming
     * one (small) statement at a time instead of the whole file. This keeps peak memory usage
     * proportional to a single row (a few KB) rather than the size of the whole aggregated
     * artifact (tens of MB), which previously caused OutOfMemoryError with a constrained heap.
     */
    private long streamFragmentIntoDb(Statement stmt, RagFragment fragment) throws SQLException {
        Path jarPath = fragment.streamJarPath();
        long[] count = { 0 };
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = ragSqlEntry(jar);
            if (entry == null) {
                LOG.warnf("RAG SQL entry not found for streamed fragment %s", fragment.source());
                return 0;
            }
            try (PushbackReader reader = new PushbackReader(
                    new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8),
                            1 << 16),
                    1)) {
                streamSplitAndConsume(reader, statement -> {
                    stmt.execute(transformStatement(statement));
                    count[0]++;
                });
            }
        } catch (IOException e) {
            throw new SQLException("Failed to stream RAG SQL from " + jarPath, e);
        }
        return count[0];
    }

    /**
     * Applies the same two transforms as {@link #injectExtensionFromSource} (inject an
     * "extension" JSON key derived from "source", and rename "version" to "quarkus_version"),
     * but to a single (small) SQL statement rather than to a whole multi-MB file.
     */
    private static String transformStatement(String statement) {
        Matcher matcher = EXTENSION_FROM_SOURCE_PATTERN.matcher(statement);
        String withExtension;
        if (matcher.find()) {
            StringBuilder sb = new StringBuilder(statement.length() + 64);
            int lastEnd = 0;
            matcher.reset();
            while (matcher.find()) {
                sb.append(statement, lastEnd, matcher.start());
                appendSourceAndExtension(sb, matcher.group(1));
                lastEnd = matcher.end();
            }
            sb.append(statement, lastEnd, statement.length());
            withExtension = sb.toString();
        } else {
            withExtension = statement;
        }
        return withExtension.replace(VERSION_KEY, QUARKUS_VERSION_KEY);
    }

    /**
     * Streaming equivalent of {@link #splitSqlStatements(String)}: reads one character at a
     * time from {@code reader} instead of from an in-memory String, invoking {@code sink} with
     * each statement as soon as it's complete. The rolling buffer only ever holds a single
     * statement, so memory usage is bounded by the largest individual statement rather than
     * the size of the whole input.
     */
    static void streamSplitAndConsume(PushbackReader reader, SqlStatementSink sink)
            throws IOException, SQLException {
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        int ic;

        while ((ic = reader.read()) != -1) {
            char c = (char) ic;

            if (c == '\n') {
                inLineComment = false;
                current.append(c);
                continue;
            }

            if (inLineComment) {
                continue;
            }

            if (c == '-' && !inSingleQuote) {
                int next = reader.read();
                if (next == '-') {
                    inLineComment = true;
                    continue;
                }
                if (next != -1) {
                    reader.unread(next);
                }
            }

            if (c == '\'') {
                if (inSingleQuote) {
                    int next = reader.read();
                    if (next == '\'') {
                        current.append('\'').append('\'');
                        continue;
                    }
                    if (next != -1) {
                        reader.unread(next);
                    }
                }
                inSingleQuote = !inSingleQuote;
            }

            if (c == ';' && !inSingleQuote) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    sink.accept(stmt);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            sink.accept(remaining);
        }
    }

    /**
     * Runs {@code action} while holding a Postgres advisory lock. Several MCP servers can share
     * one doc-search container, and two of them loading fragments at the same time deadlock
     * over the same rows; under the lock the second one waits, then finds the first one's
     * fingerprints and has nothing left to load. The lock is session-scoped, so it is released
     * when the connection closes, even if the server holding it dies mid-load.
     * <p>
     * The lock is polled rather than awaited in {@code pg_advisory_lock}, so a server stuck
     * mid-load cannot hang the others forever. Giving up fails the load instead of running it
     * unlocked, which would bring the deadlock back; the caller retries later.
     *
     * @throws IllegalStateException if the lock cannot be taken within {@link #loadLockTimeout}
     */
    void withLoadLock(String jdbcUrl, String user, String password, Runnable action) {
        try (Connection lockConn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement stmt = lockConn.createStatement()) {
            long deadline = System.nanoTime() + loadLockTimeout.toNanos();
            while (!tryLock(stmt)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Another MCP server has been loading documentation into this "
                            + "container for over " + loadLockTimeout.toMinutes() + " minutes; try again later");
                }
                Thread.sleep(500);
            }
            action.run();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reach the documentation database: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the RAG load lock", e);
        }
    }

    private static boolean tryLock(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT pg_try_advisory_lock(" + LOAD_LOCK_KEY + ")")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private void ensureSchema(String jdbcUrl, String user, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement stmt = conn.createStatement()) {
            createSchema(stmt);
        } catch (SQLException e) {
            LOG.warnf("Failed to create RAG schema: %s", e.getMessage());
        }
    }

    private static void createSchema(Statement stmt) throws SQLException {
        stmt.execute(CREATE_EXTENSION_DDL);
        stmt.execute(CREATE_TABLE_DDL);
        stmt.execute(CREATE_SOURCES_DDL);
    }

    /**
     * Reads the fingerprints recorded for each already-loaded source. A container
     * populated before {@code rag_sources} existed reports nothing, so its data is
     * treated as stale and reloaded once — the fragments replace their own rows.
     */
    Map<String, String> queryLoadedFingerprints(String jdbcUrl, String user, String password) {
        Map<String, String> fingerprints = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement stmt = conn.createStatement()) {
            createSchema(stmt);
            try (ResultSet rs = stmt.executeQuery("SELECT source, fingerprint FROM " + RAG_SOURCES_TABLE)) {
                while (rs.next()) {
                    fingerprints.put(rs.getString(1), rs.getString(2));
                }
            }
            if (!fingerprints.isEmpty()) {
                LOG.infof("Container already has RAG data for %d source(s)", fingerprints.size());
            }
        } catch (SQLException e) {
            LOG.debugf("Failed to query loaded RAG fingerprints: %s", e.getMessage());
        }
        return fingerprints;
    }

    boolean loadSql(String jdbcUrl, String user, String password,
            List<StaleFragment> fragments, String version) {
        LOG.infof("Loading %d RAG SQL fragment(s) for Quarkus %s...", fragments.size(), version);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    createSchema(stmt);

                    for (StaleFragment stale : fragments) {
                        RagFragment fragment = stale.fragment();
                        Set<String> sources = fragmentSources(fragment);

                        // Clear the fragment's own rows first. Its built-in DELETE only covers
                        // the name it was generated under, which for an aggregated fragment is
                        // not the per-row source, so rows the new content no longer replaces
                        // would otherwise be left behind.
                        deleteSources(conn, sources);

                        if (fragment.streamJarPath() != null) {
                            long count = streamFragmentIntoDb(stmt, fragment);
                            LOG.debugf("Loaded RAG source: %s (%d statement(s), streamed)",
                                    fragment.source(), count);
                        } else {
                            for (String statement : splitSqlStatements(fragment.sql())) {
                                if (!statement.isBlank()) {
                                    stmt.execute(statement);
                                }
                            }
                        }

                        recordSources(conn, sources, stale.fingerprint());
                        LOG.debugf("Loaded RAG source: %s (%d source name(s))",
                                fragment.source(), sources.size());
                    }

                    stmt.execute(CREATE_INDEX_DDL);
                }

                conn.commit();
            } catch (SQLException e) {
                // Without this the connection closes mid-transaction and the rows deleted above
                // are only restored by the driver's implicit rollback. Being explicit also means
                // a failure to undo is logged rather than swallowed by close().
                conn.rollback();
                throw e;
            }

            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + RAG_DOCUMENTS_TABLE)) {
                if (rs.next()) {
                    LOG.infof("RAG data loaded: %d total documents for Quarkus %s", rs.getLong(1), version);
                }
            }
            return true;
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to load RAG SQL for Quarkus %s", version);
            return false;
        }
    }

    private void deleteSources(Connection conn, Set<String> sources) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + RAG_DOCUMENTS_TABLE + " WHERE metadata->>'source' = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("text", sources.toArray()));
            ps.executeUpdate();
        }
    }

    private void recordSources(Connection conn, Set<String> sources, String fingerprint) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + RAG_SOURCES_TABLE + " (source, fingerprint, loaded_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (source) DO UPDATE SET "
                        + "fingerprint = EXCLUDED.fingerprint, loaded_at = now()")) {
            for (String source : sources) {
                ps.setString(1, source);
                ps.setString(2, fingerprint);
                ps.addBatch();
            }
            ps.executeBatch();
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
