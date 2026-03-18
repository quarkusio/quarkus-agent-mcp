package io.quarkus.agent.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tool for semantic search over Quarkus documentation.
 * Uses BGE Small EN v1.5 embeddings + pgvector with pre-indexed docs
 * from chappie-docling-rag.
 * <p>
 * The pgvector container is started lazily on first search via Testcontainers.
 * The embedding store is created after the container is up, using the dynamic mapped port.
 */
public class DocSearchTools {

    private static final Logger LOG = Logger.getLogger(DocSearchTools.class);

    private static final int SEARCH_CANDIDATES = 50;
    private static final int DEFAULT_MAX_RESULTS = 4;
    private static final int MAX_MAX_RESULTS = 50;
    private static final int DIMENSION = 384;

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("startup", "lifecycle"),
            Map.entry("injection", "cdi"),
            Map.entry("di", "cdi"),
            Map.entry("dependency injection", "cdi"),
            Map.entry("rest", "resteasy"),
            Map.entry("api", "rest"),
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
    EmbeddingModel embeddingModel;

    @Inject
    ContainerManager containerManager;

    @Inject
    ObjectMapper mapper;

    private final ConcurrentHashMap<String, PgVectorEmbeddingStore> embeddingStores = new ConcurrentHashMap<>();

    @Tool(name = "quarkus/searchDocs", description = "Search the Quarkus documentation using semantic search. "
            + "Returns relevant documentation chunks matching the query. "
            + "IMPORTANT: Always search the documentation BEFORE writing Quarkus code. "
            + "Use this to look up the correct APIs, annotations, configuration properties, "
            + "and best practices for any Quarkus feature (REST endpoints, CDI, Hibernate, "
            + "security, testing, native builds, etc.). "
            + "The first call may take a moment to start the documentation database. "
            + "If a projectDir is provided, the documentation version will match the project's Quarkus version.")
    ToolResponse searchDocs(
            @ToolArg(description = "The search query describing what documentation you're looking for. "
                    + "Examples: 'how to configure datasource', 'CDI dependency injection', "
                    + "'REST client configuration', 'native image build'.") String query,
            @ToolArg(description = "Maximum number of documentation chunks to return (default: 4).", required = false) Integer maxResults,
            @ToolArg(description = "Absolute path to the Quarkus project directory. "
                    + "If provided, documentation matching the project's Quarkus version is used.", required = false) String projectDir) {
        try {
            if (query == null || query.isBlank()) {
                return ToolResponse.error("Search query must not be empty.");
            }

            String quarkusVersion = null;
            if (projectDir != null && !projectDir.isBlank()) {
                quarkusVersion = QuarkusVersionDetector.detect(projectDir);
                if (quarkusVersion != null) {
                    LOG.infof("Using Quarkus %s docs for project at %s", quarkusVersion, projectDir);
                }
            }
            PgVectorEmbeddingStore store = ensureInitialized(quarkusVersion);

            Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(SEARCH_CANDIDATES)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> result = store.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = result.matches();

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
                return ToolResponse.success("No documentation found matching: " + query);
            }

            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize results: " + e.getMessage());
        } catch (Exception e) {
            LOG.error("Doc search failed", e);
            return ToolResponse.error("Doc search failed: " + e.getMessage());
        }
    }

    private PgVectorEmbeddingStore ensureInitialized(String quarkusVersion) {
        String key = quarkusVersion != null ? quarkusVersion : "__default__";

        PgVectorEmbeddingStore existing = embeddingStores.get(key);
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            // Double-check after acquiring lock
            existing = embeddingStores.get(key);
            if (existing != null) {
                return existing;
            }

            containerManager.ensureRunning(quarkusVersion);

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

            embeddingStores.put(key, store);
            return store;
        }
    }

    private List<ScoredMatch> applyMetadataBoost(List<EmbeddingMatch<TextSegment>> matches, String query) {
        String queryLower = query.toLowerCase();
        String[] queryTerms = queryLower.split("\\s+");

        List<ScoredMatch> scored = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            double score = match.score();
            TextSegment segment = match.embedded();
            if (segment == null) {
                continue;
            }

            String title = segment.metadata().getString("title");
            String repoPath = segment.metadata().getString("repo_path");
            if (title == null) {
                title = "";
            }
            if (repoPath == null) {
                repoPath = "";
            }
            String titleLower = title.toLowerCase();
            String pathLower = repoPath.toLowerCase();

            for (String term : queryTerms) {
                if (titleLower.contains(term)) {
                    score += 0.15;
                }
                if (pathLower.contains(term)) {
                    score += 0.10;
                }

                String synonym = SYNONYMS.get(term);
                if (synonym != null) {
                    if (titleLower.contains(synonym)) {
                        score += 0.12;
                    }
                    if (pathLower.contains(synonym)) {
                        score += 0.08;
                    }
                }
            }

            scored.add(new ScoredMatch(match, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    private record ScoredMatch(EmbeddingMatch<TextSegment> match, double score) {
    }
}
