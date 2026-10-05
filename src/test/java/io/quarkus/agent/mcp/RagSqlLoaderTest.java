package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class RagSqlLoaderTest {

    @Test
    void discoversAggregatedArtifactForSnapshot() throws IOException {
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

        long insertCount = 0;
        long chars = 0;
        boolean hasRest = false;
        boolean hasArc = false;
        boolean hasVector = false;
        boolean hasJsonb = false;
        // Read line by line rather than materialising the SQL: the aggregated artifact is tens of
        // MB, and above a size threshold it is discovered as a streamed fragment whose sql() is
        // null, so there is nothing to materialise anyway.
        try (BufferedReader reader = openFragment(fragment)) {
            String line;
            while ((line = reader.readLine()) != null) {
                chars += line.length() + 1;
                if (line.startsWith("INSERT INTO")) {
                    insertCount++;
                }
                hasRest |= line.contains("quarkus-rest");
                hasArc |= line.contains("quarkus-arc");
                hasVector |= line.contains("::vector");
                hasJsonb |= line.contains("::jsonb");
            }
        }

        assertTrue(hasRest, "SQL should contain REST guide data");
        assertTrue(hasArc, "SQL should contain CDI guide data");
        assertTrue(hasVector, "SQL should contain vector casts");
        assertTrue(hasJsonb, "SQL should contain jsonb casts");
        assertTrue(insertCount > 7000, "Should have 7000+ inserts, got: " + insertCount);

        System.out.println("Discovered SQL: " + chars + " chars, " + insertCount + " INSERTs"
                + (fragment.streamJarPath() != null ? " (streamed)" : " (in memory)"));
    }

    /**
     * Reads a fragment's SQL whichever way it was discovered: small fragments carry their SQL
     * in {@code sql()}, large ones only point at the jar to stream from.
     */
    private static BufferedReader openFragment(RagSqlLoader.RagFragment fragment) throws IOException {
        if (fragment.sql() != null) {
            return new BufferedReader(new StringReader(fragment.sql()));
        }
        JarFile jar = new JarFile(fragment.streamJarPath().toFile());
        JarEntry entry = jar.getJarEntry("META-INF/quarkus-rag-data.sql");
        if (entry == null) {
            entry = jar.getJarEntry("META-INF/quarkus-rag.sql");
        }
        assertNotNull(entry, "Streamed fragment should point at a jar containing RAG SQL");
        return new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                super.close();
                jar.close();
            }
        };
    }

    @Test
    void fingerprintChangesWhenFragmentContentChanges() {
        String before = "INSERT INTO rag_documents ... '{\"source\":\"quarkus-rest\"}'::jsonb);";
        String after = before + "\nINSERT INTO rag_documents ... more content;";

        assertNotEquals(RagSqlLoader.fingerprint("3.21.0", before),
                RagSqlLoader.fingerprint("3.21.0", after),
                "A regenerated fragment must not look identical to the one already loaded");
    }

    @Test
    void fingerprintChangesWhenQuarkusVersionChanges() {
        String sql = "INSERT INTO rag_documents ... '{\"source\":\"quarkus-rest\"}'::jsonb);";

        assertNotEquals(RagSqlLoader.fingerprint("3.21.0", sql),
                RagSqlLoader.fingerprint("3.22.0", sql),
                "The version is injected into row metadata, so it is part of the loaded content");
    }

    @Test
    void fingerprintIsStableForIdenticalContent() {
        String sql = "INSERT INTO rag_documents ... '{\"source\":\"quarkus-rest\"}'::jsonb);";

        assertEquals(RagSqlLoader.fingerprint("3.21.0", sql), RagSqlLoader.fingerprint("3.21.0", sql));
    }

    @Test
    void extractSourcesFindsEverySourceInAnAggregatedFragment() {
        String sql = """
                DELETE FROM rag_documents WHERE metadata->>'source' = 'quarkus-documentation';
                INSERT INTO rag_documents (...) VALUES ('1', '[]'::vector, 'a', '{"source":"quarkus-rest"}'::jsonb);
                INSERT INTO rag_documents (...) VALUES ('2', '[]'::vector, 'b', '{"source":"quarkus-arc"}'::jsonb);
                INSERT INTO rag_documents (...) VALUES ('3', '[]'::vector, 'c', '{"source":"quarkus-rest"}'::jsonb);
                """;

        assertEquals(Set.of("quarkus-documentation", "quarkus-rest", "quarkus-arc"),
                RagSqlLoader.extractSources(sql, "fallback"));
    }

    @Test
    void extractSourcesIgnoresJsonQuotedInsideAGuidesOwnText() {
        // Verbatim shape from the core docs artifact: a platform-descriptor example in
        // quarkus-platform-bom. Treating it as a row source would make a reload delete rows
        // for 'acme-platform', which is harmless only for as long as nothing owns that name.
        String sql = """
                INSERT INTO rag_documents (...) VALUES ('1', '[]'::vector, '"codestart-data" : {
                  "quarkus-magic-codestart" : {
                    "magic" : {
                      "source" : "acme-platform"
                    }
                  }
                }', '{"source":"quarkus-platform-bom","quarkus_version":"3.38.1"}'::jsonb);
                """;

        assertEquals(Set.of("quarkus-platform-bom"), RagSqlLoader.extractSources(sql, "fallback"));
    }

    @Test
    void extractSourcesFallsBackWhenFragmentNamesNoSource() {
        assertEquals(Set.of("quarkus-hibernate-orm"),
                RagSqlLoader.extractSources("INSERT INTO rag_documents VALUES (1);", "quarkus-hibernate-orm"));
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
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", "3.21.0", null);

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
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", "3.21.0", null);

        assertTrue(result.sql().contains("\"extension\":\"quarkus-vault\""),
                "Extension field should be injected");
        assertTrue(result.sql().contains("{\"source\":\"quarkus-vault\",\"extension\":\"quarkus-vault\""),
                "Source must stay the metadata object's first key, with extension right after it");
    }

    @Test
    void injectExtensionMetadataFixesVersionFields() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", "3.21.0", null);

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
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", "3.21.0", guideUrl);

        assertTrue(result.sql().contains("\"url\":\"" + guideUrl + "\""),
                "URL should be replaced with guide URL from extension metadata");
        assertFalse(result.sql().contains("quarkus.io/guides"),
                "Wrong quarkus.io URL should be gone");
    }

    @Test
    void injectExtensionMetadataRemovesWrongUrlWhenNoGuide() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", NON_CORE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", "3.21.0", null);

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
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "org.acme", "my-ext", "3.21.0", null);

        assertTrue(result.sql().contains("\"url\":\"https://docs.example.com/my-ext/guide\""),
                "Non-quarkus.io URLs should be preserved when no guide URL is available");
    }

    /**
     * Shape of a fragment generated in directory mode ({@code guidesDirectory}), as shipped by an
     * external RAG artifact: each guide carries its own source (from its {@code :extensions:}
     * header) and its own URL (from {@code guideBaseUrl}).
     */
    private static final String DIRECTORY_MODE_SQL = """
            -- quarkus-rag fragment: quarkus-documentation 1.15.0
            DELETE FROM rag_documents WHERE metadata->>'source' = 'quarkus-documentation';

            INSERT INTO rag_documents (embedding_id, embedding, text, metadata) VALUES (\
            'a1b2c3', '[0.1,0.2]'::vector, 'Integrate Claude models', \
            '{"source":"quarkus-langchain4j-anthropic","version":"1.15.0","title":"Anthropic Chat Models",\
            "url":"https://docs.quarkiverse.io/quarkus-langchain4j/dev/anthropic-chat-model",\
            "extensions":"io.quarkiverse.langchain4j:quarkus-langchain4j-anthropic",\
            "section_title":"Anthropic Chat Models","section_level":"0",\
            "section_path":"Anthropic Chat Models"}'::jsonb);

            INSERT INTO rag_documents (embedding_id, embedding, text, metadata) VALUES (\
            'd4e5f6', '[0.3,0.4]'::vector, 'Configure the OpenAI chat model', \
            '{"source":"quarkus-langchain4j-openai","version":"1.15.0","title":"OpenAI Chat Models",\
            "url":"https://docs.quarkiverse.io/quarkus-langchain4j/dev/openai-chat-model",\
            "extensions":"io.quarkiverse.langchain4j:quarkus-langchain4j-openai",\
            "section_title":"OpenAI Chat Models","section_level":"0",\
            "section_path":"OpenAI Chat Models"}'::jsonb);
            """;

    private static final String CORE_GUIDE_URL = "https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html";

    @Test
    void injectExtensionMetadataPreservesPerGuideSourceFromDirectoryMode() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-documentation", DIRECTORY_MODE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.langchain4j",
                "quarkus-langchain4j-core", "3.33.0", CORE_GUIDE_URL);

        assertTrue(result.sql().contains(
                "{\"source\":\"quarkus-langchain4j-anthropic\",\"extension\":\"quarkus-langchain4j-anthropic\""),
                "Each guide should keep the source from its :extensions: header");
        assertTrue(result.sql().contains(
                "{\"source\":\"quarkus-langchain4j-openai\",\"extension\":\"quarkus-langchain4j-openai\""),
                "Each guide should keep the source from its :extensions: header");
    }

    /**
     * The sources a reload deletes before re-running a fragment's INSERTs. A preserved source the
     * scan cannot see leaves its rows in place, and the INSERTs then collide on the baked-in
     * {@code embedding_id} primary key, rolling back the whole load.
     */
    @Test
    void preservedRowSourcesAreVisibleToTheReloadScan() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-documentation", DIRECTORY_MODE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.langchain4j",
                "quarkus-langchain4j-core", "3.33.0", CORE_GUIDE_URL);

        assertEquals(
                Set.of("quarkus-langchain4j-anthropic", "quarkus-langchain4j-openai", "quarkus-langchain4j-core"),
                RagSqlLoader.extractSources(result.sql(), result.source()),
                "A reload must delete every source the fragment writes rows for, not just its own");
    }

    @Test
    void injectExtensionMetadataReplacesSourceDeclaredForAnotherGroup() {
        String sql = DIRECTORY_MODE_SQL
                .replace("\"source\":\"quarkus-langchain4j-anthropic\"", "\"source\":\"quarkus-rest\"")
                .replace("io.quarkiverse.langchain4j:quarkus-langchain4j-anthropic", "io.quarkus:quarkus-rest");
        var fragment = new RagSqlLoader.RagFragment("quarkus-documentation", sql);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.langchain4j",
                "quarkus-langchain4j-core", "3.33.0", CORE_GUIDE_URL);

        assertFalse(result.sql().contains("\"source\":\"quarkus-rest\""),
                "A guide must not claim a source outside its extension's group, or reloading it would delete that source's rows");
        assertTrue(result.sql().contains("\"source\":\"quarkus-langchain4j-core\""),
                "The row should fall back to the extension the fragment was found for");
    }

    @Test
    void injectExtensionMetadataPreservesPerGuideUrlFromDirectoryMode() {
        var fragment = new RagSqlLoader.RagFragment("quarkus-documentation", DIRECTORY_MODE_SQL);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.langchain4j",
                "quarkus-langchain4j-core", "3.33.0", CORE_GUIDE_URL);

        assertTrue(result.sql().contains(
                "\"url\":\"https://docs.quarkiverse.io/quarkus-langchain4j/dev/anthropic-chat-model\""),
                "Each guide should keep the URL built from guideBaseUrl");
        assertTrue(result.sql().contains(
                "\"url\":\"https://docs.quarkiverse.io/quarkus-langchain4j/dev/openai-chat-model\""),
                "Each guide should keep the URL built from guideBaseUrl");
    }

    @Test
    void injectExtensionMetadataHandlesNewPluginVersionKey() {
        // New plugin format uses "version" instead of "quarkus_version"
        String newFormatSql = NON_CORE_SQL.replace("\"quarkus_version\":", "\"version\":");
        var fragment = new RagSqlLoader.RagFragment("quarkus-index", newFormatSql);
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", "3.21.0", null);

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
        var result = RagSqlLoader.injectExtensionMetadata(fragment, "io.quarkiverse.vault", "quarkus-vault", null, null);

        assertTrue(result.sql().contains("\"quarkus_version\":\"1.2.0-SNAPSHOT\""),
                "quarkus_version should remain unchanged when quarkusVersion is null");
        assertFalse(result.sql().contains("\"extension_version\""),
                "extension_version should not be added when quarkusVersion is null");
    }

    @Test
    void needsLoadingLoadsMissingAndChangedContentButDoesNotFightAnotherServer() {
        assertTrue(RagSqlLoader.needsLoading("new", null, null), "missing source");
        assertTrue(RagSqlLoader.needsLoading("new", null, "new"), "missing again, e.g. a replaced container");
        assertTrue(RagSqlLoader.needsLoading("new", "old", null), "changed upstream");
        assertFalse(RagSqlLoader.needsLoading("same", "same", null), "already there");
        assertFalse(RagSqlLoader.needsLoading("mine", "theirs", "mine"), "another server's content since");
    }
}
