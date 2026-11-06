package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class DevMcpProxyToolsTest {

    @Test
    void formatSkillIndexGroupsByCategory() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-rest", "REST extension", null, SkillReader.SkillMode.ENHANCE,
                        List.of("web"), null),
                new SkillReader.SkillInfo("quarkus-hibernate-orm", "ORM extension", null, SkillReader.SkillMode.ENHANCE,
                        List.of("data"), null),
                new SkillReader.SkillInfo("quarkus-rest-client", "REST client", null, SkillReader.SkillMode.ENHANCE,
                        List.of("web"), null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        assertTrue(index.contains("### Web"));
        assertTrue(index.contains("### Data"));
        assertTrue(index.contains("- **quarkus-rest**: REST extension"));
        assertTrue(index.contains("- **quarkus-rest-client**: REST client"));
        assertTrue(index.contains("- **quarkus-hibernate-orm**: ORM extension"));
        assertTrue(index.indexOf("### Web") < index.indexOf("### Data"));
    }

    @Test
    void formatSkillIndexUsesDefaultCategoriesForUncategorizedSkills() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-rest", "REST extension", null, SkillReader.SkillMode.ENHANCE, null, null),
                new SkillReader.SkillInfo("quarkus-security", "Security framework", null,
                        SkillReader.SkillMode.ENHANCE, null, null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        assertTrue(index.contains("### Web"));
        assertTrue(index.contains("### Security"));
    }

    @Test
    void formatSkillIndexPutsUnknownSkillsInMiscellaneous() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-rest", "REST extension", null, SkillReader.SkillMode.ENHANCE,
                        List.of("web"), null),
                new SkillReader.SkillInfo("quarkus-custom", "Custom extension", null, SkillReader.SkillMode.ENHANCE,
                        null, null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        assertTrue(index.contains("### Web"));
        assertTrue(index.contains("### Miscellaneous"));
        assertTrue(index.contains("- **quarkus-custom**: Custom extension"));
    }

    @Test
    void formatSkillIndexOrdersCategoriesCorrectly() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("s1", "desc", null, SkillReader.SkillMode.ENHANCE, List.of("security"), null),
                new SkillReader.SkillInfo("s2", "desc", null, SkillReader.SkillMode.ENHANCE, List.of("core"), null),
                new SkillReader.SkillInfo("s3", "desc", null, SkillReader.SkillMode.ENHANCE, List.of("web"), null),
                new SkillReader.SkillInfo("s4", "desc", null, SkillReader.SkillMode.ENHANCE, List.of("data"), null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        int webIdx = index.indexOf("### Web");
        int dataIdx = index.indexOf("### Data");
        int secIdx = index.indexOf("### Security");
        int coreIdx = index.indexOf("### Core");
        assertTrue(webIdx < dataIdx);
        assertTrue(dataIdx < secIdx);
        assertTrue(secIdx < coreIdx);
    }

    @Test
    void formatSkillIndexFrontmatterCategoryOverridesDefault() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-rest", "REST extension", null, SkillReader.SkillMode.ENHANCE,
                        List.of("messaging"), null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        assertTrue(index.contains("### Messaging"));
        assertFalse(index.contains("### Web"));
    }

    @Test
    void formatSkillIndexListsSkillUnderAllCategories() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-rest", "REST extension", null, SkillReader.SkillMode.ENHANCE,
                        List.of("web", "reactive"), null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        assertTrue(index.contains("### Web"));
        assertTrue(index.contains("### Reactive"));
        int webSection = index.indexOf("### Web");
        int reactiveSection = index.indexOf("### Reactive");
        String webBlock = index.substring(webSection, reactiveSection);
        assertTrue(webBlock.contains("- **quarkus-rest**: REST extension"));
        String reactiveBlock = index.substring(reactiveSection);
        assertTrue(reactiveBlock.contains("- **quarkus-rest**: REST extension"));
    }

    @Test
    void formatSkillIndexMultiCategoryPreservesOrder() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-reactive-rest", "Reactive REST", null,
                        SkillReader.SkillMode.ENHANCE, List.of("reactive", "web"), null),
                new SkillReader.SkillInfo("quarkus-hibernate-orm", "ORM", null,
                        SkillReader.SkillMode.ENHANCE, List.of("data"), null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        int webIdx = index.indexOf("### Web");
        int dataIdx = index.indexOf("### Data");
        int reactiveIdx = index.indexOf("### Reactive");
        assertTrue(webIdx < dataIdx);
        assertTrue(dataIdx < reactiveIdx);
    }

    @Test
    void formatSkillIndexOmitsDescriptionWhenNull() {
        List<SkillReader.SkillInfo> skills = List.of(
                new SkillReader.SkillInfo("quarkus-rest", null, null, SkillReader.SkillMode.ENHANCE, List.of("web"),
                        null),
                new SkillReader.SkillInfo("quarkus-arc", "CDI framework", null, SkillReader.SkillMode.ENHANCE,
                        List.of("core"), null));

        String index = DevMcpProxyTools.formatSkillIndex(skills);

        assertTrue(index.contains("- **quarkus-rest**\n"));
        assertFalse(index.contains("- **quarkus-rest**:"));
        assertTrue(index.contains("- **quarkus-arc**: CDI framework"));
    }

    @Test
    void resolveCategoriesReturnsAllExplicitCategories() {
        SkillReader.SkillInfo skill = new SkillReader.SkillInfo("quarkus-rest", "REST", null,
                SkillReader.SkillMode.ENHANCE, List.of("web", "reactive"), null);

        assertEquals(List.of("web", "reactive"), DevMcpProxyTools.resolveCategories(skill));
    }

    @Test
    void resolveCategoriesFallsBackToDefaultMap() {
        SkillReader.SkillInfo skill = new SkillReader.SkillInfo("quarkus-rest", "REST", null,
                SkillReader.SkillMode.ENHANCE, null, null);

        assertEquals(List.of("web"), DevMcpProxyTools.resolveCategories(skill));
    }

    @Test
    void resolveCategoriesReturnsMiscellaneousForUnknownSkill() {
        SkillReader.SkillInfo skill = new SkillReader.SkillInfo("quarkus-custom", "Custom", null,
                SkillReader.SkillMode.ENHANCE, null, null);

        assertEquals(List.of("miscellaneous"), DevMcpProxyTools.resolveCategories(skill));
    }

    @Test
    void titleCaseCapitalizesFirstLetter() {
        assertEquals("Web", DevMcpProxyTools.titleCase("web"));
        assertEquals("Core", DevMcpProxyTools.titleCase("core"));
    }

    @Test
    void titleCaseCapitalizesEachHyphenatedSegment() {
        assertEquals("Alt-Languages", DevMcpProxyTools.titleCase("alt-languages"));
    }

    @Test
    void titleCaseUppercasesKnownAcronyms() {
        assertEquals("AI", DevMcpProxyTools.titleCase("ai"));
        assertEquals("API", DevMcpProxyTools.titleCase("api"));
    }

    @Test
    void titleCaseHandlesNullAndEmpty() {
        assertNull(DevMcpProxyTools.titleCase(null));
        assertEquals("", DevMcpProxyTools.titleCase(""));
    }

    @Test
    void testRunToolsGetTheLongerTimeout() {
        Duration request = Duration.ofSeconds(30);
        Duration test = Duration.ofMinutes(10);

        assertEquals(test, DevMcpProxyTools.timeoutFor("devui-testing_runTest", request, test));
        assertEquals(test, DevMcpProxyTools.timeoutFor("devui-testing_runTests", request, test));
        assertEquals(test, DevMcpProxyTools.timeoutFor("devui-testing_runAffectedTests", request, test));
        assertEquals(request, DevMcpProxyTools.timeoutFor("devui-testing_cancelTests", request, test));
        assertEquals(request, DevMcpProxyTools.timeoutFor("devui-config_getConfig", request, test));
        assertEquals(request, DevMcpProxyTools.timeoutFor(null, request, test));
    }

    @Test
    void timeoutsAreNeverRetried() {
        assertFalse(DevMcpProxyTools.isRetryable("tools/call", new TimeoutException("timeout")));
        assertFalse(DevMcpProxyTools.isRetryable("tools/list", new TimeoutException("timeout")));
    }

    @Test
    void toolCallIsRetriedOnlyWhenTheConnectionFailed() {
        assertTrue(DevMcpProxyTools.isRetryable("tools/call", new ConnectException("refused")));
        assertFalse(DevMcpProxyTools.isRetryable("tools/call", new IllegalStateException("connection closed")));
        assertTrue(DevMcpProxyTools.isRetryable("tools/list", new IllegalStateException("connection closed")));
    }

    @Test
    void testRunTimeoutPointsAtCancelAndReset() {
        String message = DevMcpProxyTools.timeoutMessage("devui-testing_runTest", Duration.ofMinutes(10));

        assertTrue(message.contains("600s"));
        assertTrue(message.contains("devui-testing_cancelTests"));
        assertTrue(message.contains("devui-testing_resetTests"));
        assertTrue(message.contains("agent-mcp.dev-mcp.test-request-timeout"));
    }

    @Test
    void otherTimeoutPointsAtRequestTimeout() {
        String message = DevMcpProxyTools.timeoutMessage("devui-config_getConfig", Duration.ofSeconds(30));

        assertTrue(message.contains("30s"));
        assertTrue(message.contains("agent-mcp.dev-mcp.request-timeout"));
        assertFalse(message.contains("devui-testing_resetTests"));
    }
}
