package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagSqlLoaderTest {

    @Test
    void discoversAggregatedArtifactForSnapshot() {
        Path m2Repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        assumeTrue(Files.isDirectory(m2Repo.resolve("io/quarkus/quarkus-core")),
                "Skipped: no local Quarkus artifacts in ~/.m2/repository");

        RagSqlLoader loader = new RagSqlLoader();
        List<RagSqlLoader.RagFragment> fragments = loader.discoverSqlFragments("999-SNAPSHOT", null);

        assumeTrue(!fragments.isEmpty(),
                "Skipped: no RAG SQL fragments found locally for 999-SNAPSHOT");
        assertEquals(1, fragments.size(), "Should find exactly one aggregated fragment");

        RagSqlLoader.RagFragment fragment = fragments.get(0);
        assertNotNull(fragment.source(), "Fragment should have a source identifier");

        String sql = fragment.sql();
        assertTrue(sql.contains("INSERT INTO rag_documents"), "SQL should contain INSERT statements");
        assertTrue(sql.contains("quarkus-rest"), "SQL should contain REST guide data");
        assertTrue(sql.contains("quarkus-arc"), "SQL should contain CDI guide data");
        assertTrue(sql.contains("::vector"), "SQL should contain vector casts");
        assertTrue(sql.contains("::jsonb"), "SQL should contain jsonb casts");

        long insertCount = sql.lines()
                .filter(line -> line.startsWith("INSERT INTO"))
                .count();
        assertTrue(insertCount > 7000, "Should have 7000+ inserts, got: " + insertCount);

        System.out.println("Discovered SQL: " + sql.length() + " chars, " + insertCount + " INSERTs");
    }

    @Test
    void splitSqlStatementsHandlesSemicolonsInQuotedStrings() {
        String sql = "DELETE FROM t WHERE x = 'a;b';\nINSERT INTO t VALUES ('c;d');";
        List<String> stmts = RagSqlLoader.splitSqlStatements(sql);

        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).contains("'a;b'"));
        assertTrue(stmts.get(1).contains("'c;d'"));
    }

    @Test
    void splitSqlStatementsHandlesEscapedQuotes() {
        String sql = "INSERT INTO t VALUES ('it''s a test; with semicolons');\n"
                + "INSERT INTO t VALUES ('import java.util.UUID;');";
        List<String> stmts = RagSqlLoader.splitSqlStatements(sql);

        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).contains("it''s a test; with semicolons"));
        assertTrue(stmts.get(1).contains("import java.util.UUID;"));
    }

    @Test
    void splitSqlStatementsHandlesMultipleEscapedQuotes() {
        String sql = "INSERT INTO t VALUES ('don''t stop; can''t stop');\n"
                + "DELETE FROM t WHERE x = 'y';";
        List<String> stmts = RagSqlLoader.splitSqlStatements(sql);

        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).contains("don''t stop; can''t stop"));
        assertTrue(stmts.get(1).startsWith("DELETE"));
    }

    @Test
    void splitSqlStatementsSkipsComments() {
        String sql = "-- this is a comment\nINSERT INTO t VALUES (1);";
        List<String> stmts = RagSqlLoader.splitSqlStatements(sql);

        assertEquals(1, stmts.size());
        assertTrue(stmts.get(0).startsWith("INSERT"));
    }

    @Test
    void extractSourcePrefersRowValue() {
        String sql = "DELETE FROM rag_documents WHERE metadata->>'source' = 'quarkus-documentation';\n"
                + "INSERT INTO rag_documents VALUES ('uuid', '[1,2,3]'::vector, 'text', '{\"source\": \"quarkus-rest\"}'::jsonb);";
        assertEquals("quarkus-rest", RagSqlLoader.extractSource(sql, "fallback"));
    }

    @Test
    void extractSourceParsesDeleteStatement() {
        String sql = "DELETE FROM rag_documents WHERE metadata->>'source' = 'quarkus-rest';\n"
                + "INSERT INTO rag_documents VALUES (1);";
        assertEquals("quarkus-rest", RagSqlLoader.extractSource(sql, "fallback"));
    }

    @Test
    void extractSourceUsesFallbackWhenNoPatternPresent() {
        String sql = "INSERT INTO rag_documents VALUES (1);";
        assertEquals("my-extension", RagSqlLoader.extractSource(sql, "my-extension"));
    }

    @Test
    void extractSourceHandlesWhitespaceInRowValue() {
        String sql = "INSERT INTO rag_documents VALUES ('uuid', '[1,2,3]'::vector, 'text', '{\"source\"  :  \"quarkus-hibernate-orm\"}'::jsonb);";
        assertEquals("quarkus-hibernate-orm", RagSqlLoader.extractSource(sql, "fallback"));
    }

    @Test
    void extractSourceHandlesWhitespaceVariations() {
        String sql = "DELETE FROM rag_documents WHERE metadata ->>'source'  =  'quarkus-hibernate-orm';\n";
        assertEquals("quarkus-hibernate-orm", RagSqlLoader.extractSource(sql, "fallback"));
    }

    // ── injectExtensionMetadata tests ───────────────────────────────────────────

    private static final String NON_CORE_SQL = """
            -- quarkus-rag fragment: my-ext-deployment 1.2.0-SNAPSHOT
            DELETE FROM rag_documents WHERE metadata->>'source' = 'quarkus-index';

            INSERT INTO rag_documents (embedding_id, embedding, text, metadata) VALUES (\
            'a1b2c3', '[0.1,0.2]'::vector, 'Some documentation text', \
            '{"source":"quarkus-index","quarkus_version":"1.2.0-SNAPSHOT","title":"My Extension",\
            "url":"https://quarkus.io/guides/index","section_title":"Config","section_level":"1",\
            "section_path":"Config"}'::jsonb);

            INSERT INTO rag_documents (embedding_id, embedding, text, metadata) VALUES (\
            'd4e5f6', '[0.3,0.4]'::vector, 'More docs', \
            '{"source":"quarkus-index","quarkus_version":"1.2.0-SNAPSHOT","title":"My Extension",\
            "url":"https://quarkus.io/guides/index","section_title":"Usage","section_level":"1",\
            "section_path":"Usage"}'::jsonb);
            """;

    @Test
    void injectExtensionMetadataFixesSourceInDeleteAndInsert() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", "3.21.0", null);

        assertEquals("quarkus-vault", result.source());
        assertTrue(result.sql().contains("metadata->>'source' = 'quarkus-vault'"),
                "DELETE should use corrected source");
        assertFalse(result.sql().contains("metadata->>'source' = 'quarkus-index'"),
                "Old source should be gone from DELETE");
        assertTrue(result.sql().contains("\"source\":\"quarkus-vault\""),
                "INSERT metadata should use corrected source");
        assertFalse(result.sql().contains("\"source\":\"quarkus-index\""),
                "Old source should be gone from INSERT metadata");
    }

    @Test
    void injectExtensionMetadataAddsExtensionField() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", "3.21.0", null);

        assertTrue(result.sql().contains("\"extension\":\"quarkus-vault\""),
                "Extension field should be injected");
        assertTrue(result.sql().contains("\"extension\":\"quarkus-vault\",\"source\":\"quarkus-vault\""),
                "Extension should appear before source");
    }

    @Test
    void injectExtensionMetadataFixesVersionFields() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", "3.21.0", null);

        assertTrue(result.sql().contains("\"quarkus_version\":\"3.21.0\""),
                "quarkus_version should have the actual Quarkus version");
        assertTrue(result.sql().contains("\"extension_version\":\"1.2.0-SNAPSHOT\""),
                "extension_version should have the original extension version");
        assertTrue(result.sql().contains("\"quarkus_version\":\"3.21.0\",\"extension_version\":\"1.2.0-SNAPSHOT\""),
                "quarkus_version should come before extension_version");
    }

    @Test
    void injectExtensionMetadataReplacesUrlWhenGuideAvailable() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        String guideUrl = "https://docs.quarkiverse.io/quarkus-vault/dev/index.html";
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", "3.21.0", guideUrl);

        assertTrue(result.sql().contains("\"url\":\"" + guideUrl + "\""),
                "URL should be replaced with guide URL from extension metadata");
        assertFalse(result.sql().contains("quarkus.io/guides"),
                "Wrong quarkus.io URL should be gone");
    }

    @Test
    void injectExtensionMetadataRemovesWrongUrlWhenNoGuide() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", "3.21.0", null);

        assertFalse(result.sql().contains("quarkus.io/guides"),
                "Wrong quarkus.io URL should be removed");
        assertFalse(result.sql().contains("\"url\""),
                "No url field should remain");
    }

    @Test
    void injectExtensionMetadataPreservesNonQuarkusUrl() {
        String sqlWithCustomUrl = NON_CORE_SQL.replace(
                "https://quarkus.io/guides/index",
                "https://docs.example.com/my-ext/guide");
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", sqlWithCustomUrl);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "my-ext", "3.21.0", null);

        assertTrue(result.sql().contains("\"url\":\"https://docs.example.com/my-ext/guide\""),
                "Non-quarkus.io URLs should be preserved when no guide URL is available");
    }

    @Test
    void injectExtensionMetadataHandlesNewPluginVersionKey() {
        // New plugin format uses "version" instead of "quarkus_version"
        String newFormatSql = NON_CORE_SQL.replace("\"quarkus_version\":", "\"version\":");
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", newFormatSql);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", "3.21.0", null);

        assertTrue(result.sql().contains("\"quarkus_version\":\"3.21.0\""),
                "quarkus_version should have the actual Quarkus version");
        assertTrue(result.sql().contains("\"extension_version\":\"1.2.0-SNAPSHOT\""),
                "extension_version should have the original extension version");
        assertFalse(result.sql().contains(",\"version\":"),
                "Generic version key should be gone");
    }

    @Test
    void injectExtensionMetadataSkipsVersionFixWhenQuarkusVersionNull() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "quarkus-vault", null, null);

        assertTrue(result.sql().contains("\"quarkus_version\":\"1.2.0-SNAPSHOT\""),
                "quarkus_version should remain unchanged when quarkusVersion is null");
        assertFalse(result.sql().contains("\"extension_version\""),
                "extension_version should not be added when quarkusVersion is null");
    }
}
