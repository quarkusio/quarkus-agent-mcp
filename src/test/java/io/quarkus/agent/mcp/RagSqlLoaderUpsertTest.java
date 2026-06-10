package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RagSqlLoaderUpsertTest {

    @Test
    void testMakeInsertUpsert_simpleInsert() {
        String input = "INSERT INTO rag_documents (embedding_id, embedding, text, metadata) " +
                "VALUES ('123', '[1,2,3]', 'test', '{}'::jsonb);";
        String result = RagSqlLoader.makeInsertUpsert(input);
        
        assertTrue(result.contains("ON CONFLICT (embedding_id) DO UPDATE SET"));
        assertTrue(result.contains("embedding = EXCLUDED.embedding"));
        assertTrue(result.contains("text = EXCLUDED.text"));
        assertTrue(result.contains("metadata = EXCLUDED.metadata"));
        assertTrue(result.endsWith(";"));
    }

    @Test
    void testMakeInsertUpsert_noSemicolon() {
        String input = "INSERT INTO rag_documents (embedding_id, text) VALUES ('123', 'test')";
        String result = RagSqlLoader.makeInsertUpsert(input);
        
        assertTrue(result.contains("ON CONFLICT (embedding_id) DO UPDATE SET"));
        assertTrue(!result.endsWith(";"));
    }

    @Test
    void testMakeInsertUpsert_alreadyHasConflict() {
        String input = "INSERT INTO rag_documents (embedding_id, text) VALUES ('123', 'test') " +
                "ON CONFLICT (embedding_id) DO NOTHING;";
        String result = RagSqlLoader.makeInsertUpsert(input);
        
        // Should not add another ON CONFLICT clause
        assertEquals(input, result);
    }

    @Test
    void testMakeInsertUpsert_differentTable() {
        String input = "INSERT INTO other_table (id, text) VALUES ('123', 'test');";
        String result = RagSqlLoader.makeInsertUpsert(input);
        
        // Should not modify inserts to other tables
        assertEquals(input, result);
    }

    @Test
    void testMakeInsertUpsert_notAnInsert() {
        String input = "SELECT * FROM rag_documents;";
        String result = RagSqlLoader.makeInsertUpsert(input);
        
        // Should not modify non-INSERT statements
        assertEquals(input, result);
    }

    @Test
    void testMakeInsertUpsert_caseInsensitive() {
        String input = "insert into RAG_DOCUMENTS (embedding_id, text) values ('123', 'test');";
        String result = RagSqlLoader.makeInsertUpsert(input);
        
        assertTrue(result.toUpperCase().contains("ON CONFLICT (EMBEDDING_ID) DO UPDATE SET"));
    }
}