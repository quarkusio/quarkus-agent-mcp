package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the input validation patterns used in CreateTools.
 * These are tested directly against the regex patterns rather than through
 * the full tool call (which requires CDI).
 */
class CreateToolsValidationTest {

    // Must match the patterns in CreateTools
    private static final Pattern VALID_MAVEN_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern VALID_EXTENSIONS = Pattern.compile("^[a-zA-Z0-9._,:-]+$");

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example",
            "org.acme",
            "my-app",
            "my_app",
            "my.group.id",
            "MyApp123"
    })
    void validMavenIds(String id) {
        assertTrue(VALID_MAVEN_ID.matcher(id).matches(), id + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com example",     // space
            "my;app",          // semicolon
            "my$app",          // dollar
            "my&app",          // ampersand
            "my|app",          // pipe
            "my`app`",         // backtick
            "my$(whoami)app",  // command substitution
            "my\napp",         // newline
            "",                // empty
    })
    void invalidMavenIds(String id) {
        assertFalse(VALID_MAVEN_ID.matcher(id).matches(), id + " should be invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rest-jackson",
            "rest-jackson,hibernate-orm-panache",
            "io.quarkus:quarkus-rest:3.21.2",
            "rest-jackson,jdbc-postgresql,hibernate-orm-panache",
    })
    void validExtensions(String ext) {
        assertTrue(VALID_EXTENSIONS.matcher(ext).matches(), ext + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rest jackson",       // space
            "rest;jackson",       // semicolon
            "rest&jackson",       // ampersand
            "rest$(whoami)",      // command substitution
    })
    void invalidExtensions(String ext) {
        assertFalse(VALID_EXTENSIONS.matcher(ext).matches(), ext + " should be invalid");
    }

    @Test
    void versionValidationPattern() {
        // Same pattern as QuarkusVersionDetector.VALID_VERSION
        Pattern VALID_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+([.\\-][A-Za-z0-9]+)*$");

        assertTrue(VALID_VERSION.matcher("3.21.2").matches());
        assertTrue(VALID_VERSION.matcher("3.21.2.Final").matches());
        assertTrue(VALID_VERSION.matcher("3.21.2-SNAPSHOT").matches());
        assertTrue(VALID_VERSION.matcher("3.21.0.CR1").matches());
        assertTrue(VALID_VERSION.matcher("10.100.200").matches());

        assertFalse(VALID_VERSION.matcher("latest").matches());
        assertFalse(VALID_VERSION.matcher("3.21").matches());
        assertFalse(VALID_VERSION.matcher("${version}").matches());
        assertFalse(VALID_VERSION.matcher("latest && rm -rf /").matches());
        assertFalse(VALID_VERSION.matcher("evil.com/backdoor:latest").matches());
        assertFalse(VALID_VERSION.matcher("").matches());
        assertFalse(VALID_VERSION.matcher("3.21.2; echo pwned").matches());
    }
}
