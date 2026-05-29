package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ContainerManagerTest {

    @Test
    void supportsRagSqlForNullAndBlank() {
        assertTrue(ContainerManager.supportsRagSql(null));
        assertTrue(ContainerManager.supportsRagSql(""));
        assertTrue(ContainerManager.supportsRagSql("  "));
    }

    @Test
    void supportsRagSqlForSnapshots() {
        assertTrue(ContainerManager.supportsRagSql("999-SNAPSHOT"));
        assertTrue(ContainerManager.supportsRagSql("3.36.0-SNAPSHOT"));
        assertTrue(ContainerManager.supportsRagSql("3.35.0-SNAPSHOT"));
    }

    @Test
    void supportsRagSqlForModernVersions() {
        assertTrue(ContainerManager.supportsRagSql("3.36.1"));
        assertTrue(ContainerManager.supportsRagSql("3.36.2"));
        assertTrue(ContainerManager.supportsRagSql("3.37.0"));
        assertTrue(ContainerManager.supportsRagSql("3.40.0"));
        assertTrue(ContainerManager.supportsRagSql("4.0.0"));
    }

    @Test
    void supportsRagSqlReturnsFalseForOlderVersions() {
        assertFalse(ContainerManager.supportsRagSql("3.36.0"));
        assertFalse(ContainerManager.supportsRagSql("3.35.0"));
        assertFalse(ContainerManager.supportsRagSql("3.35.2"));
        assertFalse(ContainerManager.supportsRagSql("3.21.0"));
        assertFalse(ContainerManager.supportsRagSql("3.0.0"));
        assertFalse(ContainerManager.supportsRagSql("2.16.0"));
    }

    @Test
    void supportsRagSqlHandlesQualifiedVersions() {
        assertFalse(ContainerManager.supportsRagSql("3.36.0.Final"));
        assertFalse(ContainerManager.supportsRagSql("3.36.0.CR1"));
        assertTrue(ContainerManager.supportsRagSql("3.36.1.Final"));
        assertFalse(ContainerManager.supportsRagSql("3.35.0.Final"));
    }

    @Test
    void supportsRagSqlReturnsFalseForUnparseable() {
        assertFalse(ContainerManager.supportsRagSql("not-a-version"));
        assertFalse(ContainerManager.supportsRagSql("abc"));
    }
}
