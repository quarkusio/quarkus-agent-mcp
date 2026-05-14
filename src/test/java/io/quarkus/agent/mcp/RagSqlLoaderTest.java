package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class RagSqlLoaderTest {

    @Test
    void discoversAggregatedArtifactForSnapshot() {
        RagSqlLoader loader = new RagSqlLoader();
        List<RagSqlLoader.RagFragment> fragments = loader.discoverSqlFragments("999-SNAPSHOT", null);

        assertFalse(fragments.isEmpty(), "Should discover the quarkus-documentation-core-rag artifact");
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
    void splitSqlStatementsSkipsComments() {
        String sql = "-- this is a comment\nINSERT INTO t VALUES (1);";
        List<String> stmts = RagSqlLoader.splitSqlStatements(sql);

        assertEquals(1, stmts.size());
        assertTrue(stmts.get(0).startsWith("INSERT"));
    }

    @Test
    void extractSourceParsesDeleteStatement() {
        String sql = "DELETE FROM rag_documents WHERE metadata->>'source' = 'quarkus-rest';\n"
                + "INSERT INTO rag_documents VALUES (1);";
        assertEquals("quarkus-rest", RagSqlLoader.extractSource(sql, "fallback"));
    }

    @Test
    void extractSourceUsesFallbackWhenNoDeletePresent() {
        String sql = "INSERT INTO rag_documents VALUES (1);";
        assertEquals("my-extension", RagSqlLoader.extractSource(sql, "my-extension"));
    }

    @Test
    void extractSourceHandlesWhitespaceVariations() {
        String sql = "DELETE FROM rag_documents WHERE metadata ->>'source'  =  'quarkus-hibernate-orm';\n";
        assertEquals("quarkus-hibernate-orm", RagSqlLoader.extractSource(sql, "fallback"));
    }
}
