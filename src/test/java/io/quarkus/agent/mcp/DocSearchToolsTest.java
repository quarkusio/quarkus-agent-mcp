package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class DocSearchToolsTest {

    @Test
    void connectionFailuresAreRetried() {
        // netty's connect failure, wrapped the way the embedding client surfaces it
        assertTrue(DocSearchTools.isConnectionFailure(
                new RuntimeException(new ConnectException("Connection refused: localhost/127.0.0.1:37285"))));
        // PgVectorEmbeddingStore wraps the JDBC failure; SQLState 08001 = unable to connect
        assertTrue(DocSearchTools.isConnectionFailure(
                new RuntimeException(new SQLException("Connection to localhost:5432 refused", "08001"))));
    }

    @Test
    void otherFailuresAreNotRetried() {
        assertFalse(DocSearchTools.isConnectionFailure(
                new IllegalStateException("Another MCP server has been loading documentation for over 4 minutes")));
        assertFalse(DocSearchTools.isConnectionFailure(new RuntimeException(new TimeoutException("embedding"))));
        assertFalse(DocSearchTools.isConnectionFailure(
                new RuntimeException(new SQLException("relation does not exist", "42P01"))));
    }

    /**
     * Real {@code io.quarkus} artifact names, deliberately whole families rather than one member
     * each: core ships thirteen {@code quarkus-oidc*} extensions and several {@code quarkus-redis*}
     * and {@code quarkus-infinispan*} ones, which is what makes the bare keywords contested in the
     * first place. Alongside them, the Quarkiverse guides that an external RAG artifact tags
     * per guide.
     */
    private static final List<String> CORE_FAMILIES_AND_LANGCHAIN4J = List.of(
            "quarkus-oidc", "quarkus-oidc-client", "quarkus-oidc-client-filter",
            "quarkus-oidc-token-propagation", "quarkus-oidc-db-token-state-manager",
            "quarkus-redis-client", "quarkus-redis-cache",
            "quarkus-infinispan-client", "quarkus-infinispan-cache", "quarkus-infinispan-embedded",
            "quarkus-openshift", "quarkus-mailer",
            "quarkus-langchain4j-core", "quarkus-langchain4j-oidc-model-auth-provider",
            "quarkus-langchain4j-memory-store-redis", "quarkus-langchain4j-infinispan",
            "quarkus-langchain4j-openshift-ai", "quarkus-langchain4j-milvus");

    private static DocSearchTools.KeywordIndexBuilder indexOf(List<String> extensions) {
        var builder = new DocSearchTools.KeywordIndexBuilder();
        extensions.forEach(ext -> builder.add(ext, null, null));
        return builder;
    }

    @Test
    void aKeywordFromAnExtensionNameFiltersOnItself() {
        Map<String, String> index = indexOf(CORE_FAMILIES_AND_LANGCHAIN4J).build();

        // The filter is a substring match on the row's extension, so the keyword reaches the whole
        // family it names rather than one arbitrary member of it.
        assertEquals("oidc", index.get("oidc"));
        assertEquals("redis", index.get("redis"));
        assertEquals("infinispan", index.get("infinispan"));
        assertEquals("langchain4j", index.get("langchain4j"));
    }

    @Test
    void aKeywordNamingOneExtensionStillReachesOnlyThatOne() {
        Map<String, String> index = indexOf(CORE_FAMILIES_AND_LANGCHAIN4J).build();

        // "milvus" occurs in exactly one extension name, so filtering on it is already exact.
        assertEquals("milvus", index.get("milvus"));
        assertEquals("quarkus-langchain4j-milvus", index.get("quarkus-langchain4j-milvus"));
        assertEquals("openshift", index.get("openshift"));
    }

    @Test
    void theIndexDoesNotDependOnTheOrderExtensionsArriveIn() {
        List<String> extensions = new ArrayList<>(CORE_FAMILIES_AND_LANGCHAIN4J);
        Map<String, String> forward = indexOf(extensions).build();
        Collections.reverse(extensions);
        Map<String, String> reversed = indexOf(extensions).build();

        assertEquals(forward, reversed,
                "SELECT DISTINCT has no ORDER BY, so the index must not depend on the row order");
    }

    @Test
    void aBroadKeywordOutranksTheNarrowOneInsideIt() {
        Map<String, String> index = indexOf(CORE_FAMILIES_AND_LANGCHAIN4J).build();

        // inferExtension takes the longest keyword in the query. "langchain4j" has to stay mapped,
        // or "memory" wins and filters a general LangChain4j question to the Redis memory store.
        assertEquals("langchain4j", infer(index, "langchain4j chat memory"));
        assertEquals("langchain4j", infer(index, "how do I use the langchain4j redis embedding store"));
    }

    /** Mirrors DocSearchTools.inferExtension: the longest indexed keyword the query contains. */
    private static String infer(Map<String, String> index, String query) {
        String queryLower = query.toLowerCase();
        String best = null;
        int bestLength = 0;
        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (queryLower.contains(entry.getKey()) && entry.getKey().length() > bestLength) {
                best = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        return best;
    }

    @Test
    void topicsAndCategoriesClaimKeywordsToo() {
        var builder = new DocSearchTools.KeywordIndexBuilder();
        builder.add("quarkus-mailer", "mail,smtp", "messaging");
        builder.add("quarkus-scheduler", "cron", "miscellaneous");
        Map<String, String> index = builder.build();

        // Neither keyword occurs in an extension name, so each names its one claimant outright.
        assertEquals("quarkus-mailer", index.get("smtp"));
        assertEquals("quarkus-scheduler", index.get("cron"));
    }

    @Test
    void aTopicSharedByTwoExtensionsIsLeftUnmapped() {
        var builder = new DocSearchTools.KeywordIndexBuilder();
        builder.add("quarkus-mongodb-client", null, "data");
        builder.add("quarkus-hibernate-orm", null, "data");

        assertNull(builder.build().get("data"),
                "The shared category is in neither extension's name, so there is nothing to filter on");
    }
}
