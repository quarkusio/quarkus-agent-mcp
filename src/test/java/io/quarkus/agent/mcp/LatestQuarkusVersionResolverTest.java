package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LatestQuarkusVersionResolverTest {

    @Test
    void parseReleaseExtractsVersion() {
        String xml = """
                <metadata>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <versioning>
                    <latest>3.36.3</latest>
                    <release>3.36.3</release>
                    <versions>
                      <version>3.35.0</version>
                      <version>3.36.0</version>
                      <version>3.36.3</version>
                    </versions>
                  </versioning>
                </metadata>
                """;

        assertEquals("3.36.3", LatestQuarkusVersionResolver.parseRelease(xml));
    }

    @Test
    void parseReleaseReturnsNullForMissingTag() {
        String xml = """
                <metadata>
                  <groupId>io.quarkus</groupId>
                  <versioning>
                    <latest>3.36.3</latest>
                  </versioning>
                </metadata>
                """;

        assertNull(LatestQuarkusVersionResolver.parseRelease(xml));
    }

    @Test
    void parseReleaseReturnsNullForEmptyXml() {
        assertNull(LatestQuarkusVersionResolver.parseRelease(""));
    }

    @Test
    void parseReleaseHandlesMalformedXml() {
        assertNull(LatestQuarkusVersionResolver.parseRelease("this is not xml"));
    }

    @Test
    void parseReleaseTrimsWhitespace() {
        String xml = "<metadata><versioning><release>  3.36.3  </release></versioning></metadata>";
        assertEquals("3.36.3", LatestQuarkusVersionResolver.parseRelease(xml));
    }
}
