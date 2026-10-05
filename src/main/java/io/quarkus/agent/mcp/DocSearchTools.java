package io.quarkus.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * MCP tool for semantic search over Quarkus documentation.
 * Uses BGE Small EN v1.5 embeddings + pgvector with RAG SQL fragments
 * loaded from extension deployment JARs.
 * <p>
 * A generic pgvector container is started lazily on first search via Testcontainers.
 * SQL fragments (from {@code META-INF/quarkus-rag.sql} in deployment JARs) are loaded
 * into the container by {@link RagSqlLoader}. The embedding store is created after
 * the container is up, using the dynamic mapped port.
 */
public class DocSearchTools {

    private static final Logger LOG = Logger.getLogger(DocSearchTools.class);

    private static final int SEARCH_CANDIDATES = 50;
    private static final int DEFAULT_MAX_RESULTS = 4;
    private static final int MAX_MAX_RESULTS = 50;
    private static final int DIMENSION = 384;

    private static final int MIN_CHUNK_LENGTH = 50;
    private static final Pattern JUNK_PATTERN = Pattern.compile("^[\\-|\\s+]+$", Pattern.DOTALL);
    private static final double LEGACY_PENALTY = -0.50;
    private static final double INTERNAL_DOCS_PENALTY = -0.50;
    private static final double MODERN_GUIDE_BOOST = 0.15;

    private static final Set<String> LEGACY_GUIDES = Set.of(
            "resteasy", "resteasy-client", "resteasy-client-multipart");

    private static final Set<String> MODERN_GUIDES = Set.of(
            "rest", "rest-json", "rest-client", "rest-data-panache",
            "rest-virtual-threads", "rest-migration");

    private static final Set<String> GENERIC_KEYWORDS = Set.of(
            "quarkus", "java", "jakarta", "the", "a", "an", "how", "to", "with",
            "using", "and", "or", "for", "in", "on", "is", "it", "this", "that");

    /**
     * What each keyword narrows a search to, matched as a substring of a row's {@code extension}.
     * A value is usually a whole extension name, but a keyword shared by a family of them maps to
     * the part they have in common - {@code oidc} reaches every {@code quarkus-oidc*} extension.
     * Built once per database by {@link KeywordIndexBuilder}.
     */
    private final ConcurrentHashMap<String, String> keywordToExtension = new ConcurrentHashMap<>();

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("startup", "lifecycle"),
            Map.entry("injection", "cdi"),
            Map.entry("di", "cdi"),
            Map.entry("dependency injection", "cdi"),
            Map.entry("endpoint", "rest"),
            Map.entry("api", "rest"),
            Map.entry("json", "rest-json"),
            Map.entry("database", "datasource"),
            Map.entry("db", "datasource"),
            Map.entry("orm", "hibernate"),
            Map.entry("jpa", "hibernate"),
            Map.entry("security", "authentication"),
            Map.entry("auth", "authentication"),
            Map.entry("test", "testing"),
            Map.entry("container", "docker"),
            Map.entry("reactive", "mutiny"),
            Map.entry("config", "configuration"),
            Map.entry("deploy", "deployment"),
            Map.entry("native", "native-image"),
            Map.entry("grpc", "grpc"),
            Map.entry("graphql", "graphql"),
            Map.entry("websocket", "websockets"),
            Map.entry("kafka", "messaging"),
            Map.entry("amqp", "messaging"));

    @ConfigProperty(name = "agent-mcp.doc-search.min-score", defaultValue = "0.82")
    double minScore;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-user", defaultValue = "quarkus")
    String pgUser;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-password", defaultValue = "quarkus")
    String pgPassword;

    @ConfigProperty(name = "agent-mcp.doc-search.pg-database", defaultValue = "quarkus")
    String pgDatabase;

    @Inject
    EmbeddingClient embeddingClient;

    @Inject
    ContainerManager containerManager;

    @Inject
    ObjectMapper mapper;

    private static final String DEFAULT_VERSION_KEY = "__default__";
    private static final long INCREMENTAL_CHECK_INTERVAL_MS = 60_000;

    private final Object initLock = new Object();
    private final ConcurrentHashMap<String, PgVectorEmbeddingStore> embeddingStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastIncrementalCheck = new ConcurrentHashMap<>();

    @Tool(name = "quarkus_searchDocs", description = "Search Quarkus documentation for APIs, annotations, "
            + "configuration, and best practices. Use this for ANY Quarkus-related question -- "
            + "always prefer this over web search or generic documentation tools. "
            + "First call may take a moment to start the doc database.",
            // title set as workaround: the framework serializes "title":null when unset, which violates the MCP schema
            // see https://github.com/quarkiverse/quarkus-mcp-server/issues/748
            annotations = @Tool.Annotations(title = "quarkus_searchDocs", readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResponse searchDocs(
            @ToolArg(description = "The search query describing what documentation you're looking for. "
                    + "Examples: 'how to configure datasource', 'CDI dependency injection', "
                    + "'REST client configuration', 'native image build'.") String query,
            @ToolArg(description = "Maximum number of documentation chunks to return (default: 4).", required = false) Integer maxResults,
            @ToolArg(description = "Absolute path to the Quarkus project directory. "
                    + "Strongly recommended -- documentation is loaded from the project's extension "
                    + "dependencies, so searches without projectDir may return no results. "
                    + "Also selects documentation matching the project's Quarkus version.", required = false) String projectDir,
            @ToolArg(description = "Optional extension name to restrict results to a specific extension's "
                    + "documentation (e.g. 'quarkus-json-rpc', 'quarkus-rest'). "
                    + "When set, only documentation chunks from that extension are returned. "
                    + "Works for both core Quarkus extensions and Quarkiverse extensions.", required = false) String extension) {
        try {
            if (query == null || query.isBlank()) {
                return ToolResponse.error("Search query must not be empty.");
            }

            if (embeddingClient.isFailed()) {
                String failure = embeddingClient.getFailureMessage();
                if (containerManager.retryWarmUpIfFailed()) {
                    return ToolResponse.success("Documentation search failed to start (" + failure
                            + "). Retrying now; please try again in a few seconds.");
                }
                return ToolResponse.error("Documentation search is unavailable: " + failure);
            }
            if (!embeddingClient.isReady()) {
                return ToolResponse.success(
                        "Documentation search is still warming up (starting doc database). Please retry in a few seconds.");
            }

            String quarkusVersion = null;
            if (projectDir != null && !projectDir.isBlank()) {
                quarkusVersion = QuarkusVersionDetector.detect(projectDir);
                if (quarkusVersion != null) {
                    LOG.infof("Using Quarkus %s docs for project at %s", quarkusVersion, projectDir);
                }
            }
            List<EmbeddingMatch<TextSegment>> rawMatches;
            try {
                rawMatches = findMatches(query, extension, quarkusVersion, projectDir);
            } catch (RuntimeException e) {
                // The shared container may have been restarted on new ports, or replaced, by another
                // MCP server or the user: resolve it afresh and try once more. Other failures (timeouts,
                // a failed image pull) would only take as long again, so they are not retried.
                if (!isConnectionFailure(e)) {
                    throw e;
                }
                LOG.warnf("Doc search could not reach the doc database (%s) — reconnecting and retrying",
                        e.getMessage());
                reconnect(quarkusVersion);
                rawMatches = findMatches(query, extension, quarkusVersion, projectDir);
            }

            containerManager.markUsed(quarkusVersion);
            containerManager.markUsed(null);

            // Check for newly added extensions (rate-limited)
            if (projectDir != null && !projectDir.isBlank()) {
                maybeLoadIncrementalRagData(quarkusVersion, projectDir);
            }

            List<EmbeddingMatch<TextSegment>> matches = rawMatches.stream()
                    .filter(m -> m.embedded() != null && !isJunkChunk(m.embedded()))
                    .toList();

            List<ScoredMatch> boosted = applyMetadataBoost(matches, query);

            int limit = (maxResults != null && maxResults > 0) ? Math.min(maxResults, MAX_MAX_RESULTS) : DEFAULT_MAX_RESULTS;
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, boosted.size()); i++) {
                ScoredMatch sm = boosted.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("score", Math.round(sm.score * 1000.0) / 1000.0);
                entry.put("text", sm.match.embedded().text());

                Map<String, String> metadata = new LinkedHashMap<>();
                sm.match.embedded().metadata().toMap().forEach((k, v) -> metadata.put(k, String.valueOf(v)));
                if (!metadata.isEmpty()) {
                    entry.put("metadata", metadata);
                }
                results.add(entry);
            }

            if (results.isEmpty()) {
                StringBuilder msg = new StringBuilder("No documentation found matching: " + query);
                if (projectDir == null || projectDir.isBlank()) {
                    msg.append("\n\nHint: You did not provide a projectDir. Documentation is loaded from "
                            + "the project's extension dependencies, so results are significantly more "
                            + "accurate when projectDir is set. Retry with the projectDir parameter.");
                } else {
                    msg.append("\n\nHint: Try rephrasing your query with different or simpler keywords. "
                            + "Documentation is loaded from extension JARs in the project -- if the "
                            + "relevant extension is not a project dependency, its docs won't appear here.");
                }
                return ToolResponse.success(msg.toString());
            }

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
            if (quarkusVersion != null && containerManager.isUsingFallback(quarkusVersion)) {
                json += "\n\nNote: Version-specific docs for Quarkus " + quarkusVersion
                        + " are not available. Results are from the latest documentation.";
            }
            return ToolResponse.success(json);
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize results: " + e.getMessage());
        } catch (Exception e) {
            LOG.error("Doc search failed", e);
            return ToolResponse.error("Doc search failed: " + e.getMessage());
        }
    }

    private List<EmbeddingMatch<TextSegment>> findMatches(String query, String extension, String quarkusVersion,
            String projectDir) {
        PgVectorEmbeddingStore store = ensureInitialized(quarkusVersion, projectDir);
        containerManager.ensureEmbeddingServer();

        Embedding queryEmbedding = new Embedding(embeddingClient.embed(query));

        String effectiveExtension = extension;
        if ((effectiveExtension == null || effectiveExtension.isBlank())) {
            effectiveExtension = inferExtension(query);
            if (effectiveExtension != null) {
                LOG.debugf("Auto-inferred extension filter: *%s*", effectiveExtension);
            }
        }

        Filter sourceFilter = null;
        if (effectiveExtension != null && !effectiveExtension.isBlank()) {
            sourceFilter = new ContainsString("extension", effectiveExtension.trim());
        }

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .filter(sourceFilter)
                .maxResults(SEARCH_CANDIDATES)
                .minScore(minScore)
                .build();

        return store.search(searchRequest).matches();
    }

    /** True if the failure means a doc container could not be reached at the address this server cached. */
    static boolean isConnectionFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
            // SQLState class 08: connection exception (refused, broken, cannot be established)
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Drops every cached connection to the doc containers so the next search resolves them again. */
    private void reconnect(String quarkusVersion) {
        embeddingStores.remove(quarkusVersion != null ? quarkusVersion : DEFAULT_VERSION_KEY);
        containerManager.invalidate(quarkusVersion);
        containerManager.invalidate(null);
    }

    private PgVectorEmbeddingStore ensureInitialized(String quarkusVersion, String projectDir) {
        String key = quarkusVersion != null ? quarkusVersion : DEFAULT_VERSION_KEY;

        PgVectorEmbeddingStore existing = embeddingStores.get(key);
        if (existing != null) {
            return existing;
        }

        synchronized (initLock) {
            existing = embeddingStores.get(key);
            if (existing != null) {
                return existing;
            }

            containerManager.ensureRunning(quarkusVersion, projectDir);

            String host = containerManager.getHost(quarkusVersion);
            int port = containerManager.getMappedPort(quarkusVersion);

            LOG.infof("Connecting to pgvector at %s:%d (Quarkus %s)...", host, port,
                    quarkusVersion != null ? quarkusVersion : "default");
            PgVectorEmbeddingStore store = PgVectorEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .database(pgDatabase)
                    .user(pgUser)
                    .password(pgPassword)
                    .table("rag_documents")
                    .dimension(DIMENSION)
                    .createTable(false)
                    .useIndex(false)
                    .build();
            LOG.info("Connected to pgvector embedding store.");

            if (keywordToExtension.isEmpty()) {
                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + pgDatabase;
                buildKeywordIndex(jdbcUrl, pgUser, pgPassword);
            }

            embeddingStores.put(key, store);
            return store;
        }
    }

    private void maybeLoadIncrementalRagData(String quarkusVersion, String projectDir) {
        String key = projectDir + ":" + (quarkusVersion != null ? quarkusVersion : "default");
        long now = System.currentTimeMillis();
        Long lastCheck = lastIncrementalCheck.get(key);
        if (lastCheck != null && (now - lastCheck) < INCREMENTAL_CHECK_INTERVAL_MS) {
            return;
        }
        lastIncrementalCheck.put(key, now);

        // In the background: it can wait minutes on the load lock while another MCP server sharing
        // the container is loading, and the search it piggybacks on already has its results
        Thread.ofVirtual().name("rag-incremental-check").start(() -> {
            try {
                containerManager.loadIncrementalRagData(quarkusVersion, projectDir);
            } catch (Exception e) {
                LOG.debugf("Incremental RAG check failed: %s", e.getMessage());
            }
        });
    }

    private boolean isJunkChunk(TextSegment segment) {
        String text = segment.text().trim();
        if (text.length() < MIN_CHUNK_LENGTH) {
            return true;
        }
        if (JUNK_PATTERN.matcher(text).matches()) {
            return true;
        }
        return text.startsWith("Configuration property fixed at build time");
    }

    private List<ScoredMatch> applyMetadataBoost(List<EmbeddingMatch<TextSegment>> matches, String query) {
        String queryLower = query.toLowerCase();
        String[] queryTerms = queryLower.split("\\s+");

        List<ScoredMatch> scored = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            double score = match.score();
            TextSegment segment = match.embedded();

            String title = metadataOrEmpty(segment, "title");
            String repoPath = metadataOrEmpty(segment, "repo_path");
            String topics = metadataOrEmpty(segment, "topics");
            String categories = metadataOrEmpty(segment, "categories");
            String sectionTitle = metadataOrEmpty(segment, "section_title");
            String sectionPath = metadataOrEmpty(segment, "section_path");
            String summary = metadataOrEmpty(segment, "summary");

            for (String term : queryTerms) {
                if (title.contains(term)) {
                    score += 0.15;
                }
                if (repoPath.contains(term)) {
                    score += 0.10;
                }
                if (topics.contains(term)) {
                    score += 0.15;
                }
                if (categories.contains(term)) {
                    score += 0.10;
                }
                if (sectionTitle.contains(term) || sectionPath.contains(term)) {
                    score += 0.08;
                }
                if (summary.contains(term)) {
                    score += 0.05;
                }

                String synonym = SYNONYMS.get(term);
                if (synonym != null) {
                    if (title.contains(synonym) || topics.contains(synonym)) {
                        score += 0.12;
                    }
                    if (repoPath.contains(synonym) || sectionPath.contains(synonym)) {
                        score += 0.08;
                    }
                }
            }

            if (matchesGuide(LEGACY_GUIDES, title, repoPath) || topics.contains("resteasy-classic")) {
                score += LEGACY_PENALTY;
            }
            if (topics.contains("internals") || topics.contains("documentation")) {
                score += INTERNAL_DOCS_PENALTY;
            }
            if (matchesGuide(MODERN_GUIDES, title, repoPath) || topics.contains("resteasy-reactive")) {
                score += MODERN_GUIDE_BOOST;
            }

            scored.add(new ScoredMatch(match, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    private static boolean matchesGuide(Set<String> guides, String title, String repoPath) {
        if (guides.contains(title)) {
            return true;
        }
        for (String guide : guides) {
            if (repoPath.contains("/" + guide + ".") || repoPath.contains("/" + guide + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String metadataOrEmpty(TextSegment segment, String key) {
        String value = segment.metadata().getString(key);
        return value != null ? value.toLowerCase() : "";
    }

    private String inferExtension(String query) {
        String queryLower = query.toLowerCase();
        String bestMatch = null;
        int bestLength = 0;
        for (Map.Entry<String, String> entry : keywordToExtension.entrySet()) {
            String keyword = entry.getKey();
            if (queryLower.contains(keyword) && keyword.length() > bestLength) {
                bestMatch = entry.getValue();
                bestLength = keyword.length();
            }
        }
        return bestMatch;
    }

    void buildKeywordIndex(String jdbcUrl, String user, String password) {
        KeywordIndexBuilder builder = new KeywordIndexBuilder();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, user, password);
                java.sql.Statement stmt = conn.createStatement();
                java.sql.ResultSet rs = stmt.executeQuery(
                        "SELECT DISTINCT metadata->>'extension' AS ext, "
                                + "metadata->>'topics' AS topics, "
                                + "metadata->>'categories' AS categories "
                                + "FROM rag_documents "
                                + "WHERE metadata->>'extension' IS NOT NULL")) {
            while (rs.next()) {
                builder.add(rs.getString("ext"), rs.getString("topics"), rs.getString("categories"));
            }
        } catch (Exception e) {
            // Keep whatever was collected before the failure rather than starting with nothing.
            LOG.debugf("Failed to build keyword index: %s", e.getMessage());
        }
        Map<String, String> index = builder.build();
        keywordToExtension.putAll(index);
        LOG.infof("Built keyword index with %d entries from RAG metadata (%d contested keyword(s) left unmapped)",
                index.size(), builder.claimCount() - index.size());
    }

    /**
     * Works out what each keyword should filter on, over all extensions at once rather than one
     * row at a time. A keyword more than one extension claims used to go to whichever row the
     * {@code DISTINCT} scan happened to return first, which became a real problem once an external
     * RAG artifact started tagging rows per guide: {@code quarkus-langchain4j-oidc-model-auth-provider}
     * claims the bare keyword {@code oidc} alongside the thirteen core {@code quarkus-oidc*}
     * extensions, and the winner was whatever Postgres listed first.
     */
    static final class KeywordIndexBuilder {

        private final Map<String, Set<String>> claims = new HashMap<>();
        private final Set<String> fromExtensionName = new HashSet<>();

        /** Records the keywords {@code ext} lays claim to, from its own name and its doc metadata. */
        void add(String ext, String topics, String categories) {
            if (ext == null || ext.isBlank()) {
                return;
            }
            String extLower = ext.toLowerCase().trim();
            String withoutPrefix = extLower.replaceFirst("^quarkus-", "");

            claimFromName(extLower, ext);
            claimFromName(withoutPrefix, ext);
            for (String part : withoutPrefix.split("-")) {
                claimFromName(part, ext);
            }
            claimAll(topics, ext);
            claimAll(categories, ext);
        }

        private void claimFromName(String keyword, String ext) {
            if (claim(keyword, ext)) {
                fromExtensionName.add(keyword);
            }
        }

        private void claimAll(String text, String ext) {
            if (text == null) {
                return;
            }
            for (String token : text.toLowerCase().split("[,\\s]+")) {
                claim(token.trim(), ext);
            }
        }

        private boolean claim(String keyword, String ext) {
            if (keyword.length() <= 2 || GENERIC_KEYWORDS.contains(keyword)) {
                return false;
            }
            claims.computeIfAbsent(keyword, k -> new LinkedHashSet<>()).add(ext);
            return true;
        }

        /** How many distinct keywords were claimed, mapped or not. */
        int claimCount() {
            return claims.size();
        }

        Map<String, String> build() {
            Map<String, String> index = new HashMap<>();
            for (Map.Entry<String, Set<String>> claim : claims.entrySet()) {
                String filter = resolve(claim.getKey(), claim.getValue());
                if (filter != null) {
                    index.put(claim.getKey(), filter);
                }
            }
            return index;
        }

        /**
         * What a keyword should filter on, or null when that cannot be decided.
         * <p>
         * {@link #inferExtension} feeds a {@link ContainsString} filter, which is a substring match
         * over the row's {@code extension}. So a keyword taken from an extension's own name can
         * simply filter on itself: {@code oidc} reaches every {@code quarkus-oidc*} extension, and
         * {@code langchain4j} reaches all thirty-odd of its own, instead of being awarded to one
         * arbitrary member of the family. Naming a single extension would hide the rest, and
         * dropping the keyword would let a shorter, narrower one win the inference instead -
         * "langchain4j chat memory" would end up filtered to the Redis chat-memory store.
         * <p>
         * A keyword that appears only in doc metadata has no such anchor, so it is usable only
         * while exactly one extension claims it. Anything still contested is dropped, which costs
         * no more than an unfiltered search.
         */
        private String resolve(String keyword, Set<String> claimants) {
            if (fromExtensionName.contains(keyword)) {
                return keyword;
            }
            return claimants.size() == 1 ? claimants.iterator().next() : null;
        }
    }

    private record ScoredMatch(EmbeddingMatch<TextSegment> match, double score) {
    }
}
