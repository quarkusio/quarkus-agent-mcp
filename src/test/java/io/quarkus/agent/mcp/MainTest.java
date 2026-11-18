package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void versionIsFilteredFromPom() {
        String version = Main.version();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), "Unexpected version: " + version);
    }

    @Test
    void versionFlagPrintsVersionToStdoutAndReturns() {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            Main.main(new String[] { "--version" });
            Main.main(new String[] { "-V" });
        } finally {
            System.setOut(original);
        }
        String expected = Main.version() + System.lineSeparator();
        assertEquals(expected + expected, out.toString(StandardCharsets.UTF_8));
    }
}
