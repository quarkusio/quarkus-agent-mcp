package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for QuarkusProcessManager validation and build tool detection.
 * These tests focus on the validation/detection logic without actually
 * starting Quarkus processes (which would require a real project).
 */
class QuarkusProcessManagerTest {

    @TempDir
    Path tempDir;

    private QuarkusProcessManager manager;

    @BeforeEach
    void setUp() {
        manager = new QuarkusProcessManager();
    }

    @Test
    void startThrowsForNullProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.start(null, null, null, null));
    }

    @Test
    void startThrowsForEmptyProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.start("", null, null, null));
    }

    @Test
    void startThrowsForBlankProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.start("   ", null, null, null));
    }

    @Test
    void stopThrowsForNullProjectDir() {
        assertThrows(IllegalArgumentException.class, () -> manager.stop(null));
    }

    @Test
    void stopThrowsForNonExistentInstance() {
        assertThrows(IllegalStateException.class, () -> manager.stop("/nonexistent"));
    }

    @Test
    void restartThrowsForNonExistentInstance() {
        assertThrows(IllegalStateException.class, () -> manager.restart("/nonexistent"));
    }

    @Test
    void getInstanceReturnsNullForUnknownProject() {
        assertNull(manager.getInstance("/unknown/project"));
    }

    @Test
    void listInstancesEmptyByDefault() {
        Map<String, String> instances = manager.listInstances();
        assertTrue(instances.isEmpty());
    }

    @Test
    void throwsWhenNoBuildToolDetected() {
        // tempDir has no pom.xml or build.gradle
        assertThrows(IllegalArgumentException.class,
                () -> manager.start(tempDir.toString(), null, null, null));
    }

    @Test
    void throwsForNonDirectoryPath() throws IOException {
        Path file = tempDir.resolve("afile.txt");
        Files.writeString(file, "not a directory");

        assertThrows(IllegalArgumentException.class,
                () -> manager.start(file.toString(), "maven", null, null));
    }

    @Test
    void startThrowsForPortZero() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.start(tempDir.toString(), "maven", 0, null));
    }

    @Test
    void startThrowsForNegativePort() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.start(tempDir.toString(), "maven", -1, null));
    }

    @Test
    void startThrowsForPortAbove65535() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.start(tempDir.toString(), "maven", 70000, null));
    }

    @Test
    void processBuilderIncludesPortArgs() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", 9090, (String) null, (String) null);
        assertTrue(pb.command().contains("-Dquarkus.http.port=9090"));
        assertTrue(pb.command().contains("-Dquarkus.http.test-port=0"));
    }

    @Test
    void processBuilderOmitsPortArgsWhenNull() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null);
        assertFalse(pb.command().stream().anyMatch(arg -> arg.startsWith("-Dquarkus.http.port=")));
        assertFalse(pb.command().stream().anyMatch(arg -> arg.startsWith("-Dquarkus.http.test-port=")));
    }

    @Test
    void processBuilderIncludesMavenProfile() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, "myprofile", (String) null);
        assertTrue(pb.command().contains("-Pmyprofile"));
    }

    @Test
    void processBuilderIncludesMultipleMavenProfiles() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, "p1,p2", (String) null);
        assertTrue(pb.command().contains("-Pp1,p2"));
    }

    @Test
    void processBuilderOmitsProfileArgWhenNull() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null);
        assertFalse(pb.command().stream().anyMatch(arg -> arg.startsWith("-P")));
    }

    @Test
    void processBuilderOmitsProfileArgWhenBlank() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, "   ", (String) null);
        assertFalse(pb.command().stream().anyMatch(arg -> arg.startsWith("-P")));
    }

    private void initOptionalFields() throws Exception {
        initOptionalFields("dev");
    }

    private void initOptionalFields(String mode) throws Exception {
        initOptionalFields(mode, Optional.empty());
    }

    private void initOptionalFields(String mode, Optional<String> extraArgs) throws Exception {
        for (String fieldName : new String[] { "gradleCmd", "appLogEnabled" }) {
            Field f = QuarkusProcessManager.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(manager, Optional.empty());
        }
        Field configExtraArgsField = QuarkusProcessManager.class.getDeclaredField("configExtraArgs");
        configExtraArgsField.setAccessible(true);
        configExtraArgsField.set(manager, extraArgs);
        Field modeField = QuarkusProcessManager.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.set(manager, mode);
    }

    @Test
    void detectsBuildToolMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        // Use reflection to test detectBuildTool directly
        Method m = QuarkusProcessManager.class.getDeclaredMethod("detectBuildTool", String.class);
        m.setAccessible(true);
        assertEquals("maven", m.invoke(manager, tempDir.toString()));
    }

    @Test
    void detectsBuildToolGradle() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "// gradle");
        Method m = QuarkusProcessManager.class.getDeclaredMethod("detectBuildTool", String.class);
        m.setAccessible(true);
        assertEquals("gradle", m.invoke(manager, tempDir.toString()));
    }

    @Test
    void detectsBuildToolGradleKts() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// gradle kts");
        Method m = QuarkusProcessManager.class.getDeclaredMethod("detectBuildTool", String.class);
        m.setAccessible(true);
        assertEquals("gradle", m.invoke(manager, tempDir.toString()));
    }

    @Test
    void isPortAvailableReturnsTrueForFreePort() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        assertTrue(QuarkusProcessManager.isPortAvailable(port));
    }

    @Test
    void isPortAvailableReturnsFalseForOccupiedPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            assertFalse(QuarkusProcessManager.isPortAvailable(port));
        }
    }

    @Test
    void findAvailablePortSkipsOccupiedPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int occupied = ss.getLocalPort();
            int found = QuarkusProcessManager.findAvailablePort(occupied);
            assertTrue(found > occupied);
            assertTrue(QuarkusProcessManager.isPortAvailable(found));
        }
    }

    @Test
    void processBuilderIncludesExtraArgs() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, "-Ddebug=5005");
        assertTrue(pb.command().contains("-Ddebug=5005"));
    }

    @Test
    void processBuilderIncludesMultipleExtraArgTokens() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, "-Dfoo=bar -Dbaz=qux");
        assertTrue(pb.command().contains("-Dfoo=bar"));
        assertTrue(pb.command().contains("-Dbaz=qux"));
    }

    @Test
    void processBuilderOmitsExtraArgsWhenNull() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields();
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        int sizeBefore = ((ProcessBuilder) m.invoke(
                manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null)).command().size();
        int sizeBlank = ((ProcessBuilder) m.invoke(
                manager, tempDir.toString(), "maven", (Integer) null, (String) null, "   ")).command().size();
        assertEquals(sizeBefore, sizeBlank);
    }

    @Test
    void normalizesPathsConsistently() throws Exception {
        Method m = QuarkusProcessManager.class.getDeclaredMethod("normalize", String.class);
        m.setAccessible(true);

        String path1 = (String) m.invoke(manager, tempDir.toString());
        String path2 = (String) m.invoke(manager, tempDir.toString() + "/./");

        assertEquals(path1, path2);
    }

    @Test
    void modeDefaultIsDev() {
        QuarkusProcessManager pm = new QuarkusProcessManager();
        try {
            Field f = QuarkusProcessManager.class.getDeclaredField("mode");
            f.setAccessible(true);
            f.set(pm, "dev");
            assertEquals("dev", pm.getMode());
            assertTrue(pm.isDevMode());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void isDevModeReturnsFalseForProd() {
        QuarkusProcessManager pm = new QuarkusProcessManager();
        try {
            Field f = QuarkusProcessManager.class.getDeclaredField("mode");
            f.setAccessible(true);
            f.set(pm, "prod");
            assertFalse(pm.isDevMode());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void processBuilderUsesMavenDevModeByDefault() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("dev");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null);
        assertTrue(pb.command().contains("quarkus:dev"));
        assertTrue(pb.command().contains("-Dquarkus.dev-mcp.enabled=true"));
        assertTrue(pb.command().contains("-Dquarkus.console.enabled=false"));
    }

    @Test
    void processBuilderUsesMavenProdMode() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("prod");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null);
        assertTrue(pb.command().contains("quarkus:run"));
        assertFalse(pb.command().contains("quarkus:dev"));
        assertFalse(pb.command().contains("-Dquarkus.dev-mcp.enabled=true"));
        assertFalse(pb.command().contains("-Dquarkus.console.enabled=false"));
        assertFalse(pb.command().contains("-Dquarkus.profile=test"));
    }

    @Test
    void processBuilderUsesMavenTestMode() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("test");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null);
        assertTrue(pb.command().contains("quarkus:run"));
        assertTrue(pb.command().contains("-Dquarkus.profile=test"));
        assertFalse(pb.command().contains("quarkus:dev"));
    }

    @Test
    void processBuilderUsesGradleProdMode() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "// gradle");
        initOptionalFields("prod");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "gradle", (Integer) null, (String) null, (String) null);
        assertTrue(pb.command().contains("quarkusRun"));
        assertFalse(pb.command().contains("quarkusDev"));
        assertFalse(pb.command().contains("-Dquarkus.profile=test"));
    }

    @Test
    void processBuilderUsesGradleTestMode() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "// gradle");
        initOptionalFields("test");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "gradle", (Integer) null, (String) null, (String) null);
        assertTrue(pb.command().contains("quarkusRun"));
        assertTrue(pb.command().contains("-Dquarkus.profile=test"));
        assertFalse(pb.command().contains("quarkusDev"));
    }

    @Test
    void processBuilderProdModeIncludesPortArgs() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("prod");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", 9090, (String) null, (String) null);
        assertTrue(pb.command().contains("quarkus:run"));
        assertTrue(pb.command().contains("-Dquarkus.http.port=9090"));
    }

    @Test
    void processBuilderProdModeIncludesMavenProfile() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("prod");
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, "myprofile", (String) null);
        assertTrue(pb.command().contains("quarkus:run"));
        assertTrue(pb.command().contains("-Pmyprofile"));
    }

    @Test
    void validateModeAcceptsValidModes() throws Exception {
        for (String validMode : new String[] { "dev", "test", "prod" }) {
            QuarkusProcessManager pm = new QuarkusProcessManager();
            Field f = QuarkusProcessManager.class.getDeclaredField("mode");
            f.setAccessible(true);
            f.set(pm, validMode);
            pm.validateMode();
        }
    }

    @Test
    void validateModeRejectsInvalidMode() throws Exception {
        QuarkusProcessManager pm = new QuarkusProcessManager();
        Field f = QuarkusProcessManager.class.getDeclaredField("mode");
        f.setAccessible(true);
        f.set(pm, "invalid");
        assertThrows(IllegalStateException.class, pm::validateMode);
    }

    @Test
    void processBuilderIncludesConfigExtraArgs() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("dev", Optional.of("-DdebugHost=*"));
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, (String) null);
        assertTrue(pb.command().contains("-DdebugHost=*"));
    }

    @Test
    void processBuilderAppendsToolExtraArgsAfterConfigExtraArgs() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        initOptionalFields("dev", Optional.of("-DdebugHost=*"));
        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, tempDir.toString(), "maven", (Integer) null, (String) null, "-Ddebug=5005");
        int configIdx = pb.command().indexOf("-DdebugHost=*");
        int toolIdx = pb.command().indexOf("-Ddebug=5005");
        assertTrue(configIdx >= 0, "config extra arg should be present");
        assertTrue(toolIdx >= 0, "tool extra arg should be present");
        assertTrue(configIdx < toolIdx, "config extra args should come before tool extra args");
    }

    /** Makes tempDir look like a Quarkus project, which attaching requires. */
    private void writePom() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
    }

    private void writeAppProperties(String content) throws IOException {
        Path resources = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.writeString(resources.resolve("application.properties"), content);
    }

    @Test
    void registerCreatesExternalInstance() throws Exception {
        initOptionalFields();
        writePom();
        // Use a real open port so the liveness probe in getStatus() returns RUNNING.
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            manager.register(tempDir.toString(), port);

            QuarkusInstance instance = manager.getInstance(tempDir.toString());
            assertNotNull(instance);
            assertTrue(instance.isExternal());
            assertEquals(port, instance.getHttpPort());
            assertEquals(QuarkusInstance.Status.RUNNING, instance.getStatus());
        }
    }

    @Test
    void registerThrowsForInvalidPort() throws Exception {
        writePom();
        assertThrows(IllegalArgumentException.class, () -> manager.register(tempDir.toString(), 0));
        assertThrows(IllegalArgumentException.class, () -> manager.register(tempDir.toString(), 70000));
    }

    @Test
    void validatePortRejectsOutOfRangePorts() {
        assertThrows(IllegalArgumentException.class, () -> QuarkusProcessManager.validatePort(0));
        assertThrows(IllegalArgumentException.class, () -> QuarkusProcessManager.validatePort(70000));
        assertThrows(IllegalArgumentException.class, () -> QuarkusProcessManager.validatePort(65536));
        assertThrows(IllegalArgumentException.class, () -> QuarkusProcessManager.validatePort(-1));
        // boundary values must pass
        QuarkusProcessManager.validatePort(1);
        QuarkusProcessManager.validatePort(65535);
    }

    @Test
    void registerNormalizedCreatesExternalInstance() throws Exception {
        initOptionalFields();
        writePom();
        String normalizedDir = manager.validateAttachable(tempDir.toString());
        manager.registerNormalized(normalizedDir, 9191);

        QuarkusInstance instance = manager.getInstance(normalizedDir);
        assertNotNull(instance);
        assertTrue(instance.isExternal());
        assertEquals(9191, instance.getHttpPort());
    }

    @Test
    void registerThrowsForNonExistentDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.register(tempDir.resolve("nope").toString(), 8080));
    }

    @Test
    void registerThrowsWhenNotAQuarkusProject() {
        assertThrows(IllegalArgumentException.class, () -> manager.register(tempDir.toString(), 8080));
    }

    @Test
    void registerThrowsWhenInstanceAlreadyAlive() throws Exception {
        initOptionalFields();
        writePom();
        manager.register(tempDir.toString(), 8080);

        assertThrows(IllegalStateException.class, () -> manager.register(tempDir.toString(), 8080));
    }

    @Test
    void registerReplacesDeadInstance() throws Exception {
        initOptionalFields();
        writePom();
        manager.register(tempDir.toString(), 8080);
        manager.stop(tempDir.toString());

        manager.register(tempDir.toString(), 9090);

        QuarkusInstance instance = manager.getInstance(tempDir.toString());
        assertNotNull(instance);
        assertEquals(9090, instance.getHttpPort());
    }

    @Test
    void restartThrowsForExternalInstance() throws Exception {
        initOptionalFields();
        writePom();
        manager.register(tempDir.toString(), 8080);

        assertThrows(IllegalStateException.class, () -> manager.restart(tempDir.toString()));
    }

    @Test
    void detectHttpPortReadsFromProperties() throws Exception {
        writeAppProperties("quarkus.http.port=9191\n");

        assertEquals(9191, QuarkusProcessManager.detectHttpPort(tempDir.toString()));
    }

    @Test
    void detectHttpPortPrefersDevProfileOverride() throws Exception {
        writeAppProperties("quarkus.http.port=9191\n%dev.quarkus.http.port=8081\n");

        assertEquals(8081, QuarkusProcessManager.detectHttpPort(tempDir.toString()));
    }

    @Test
    void detectHttpPortReadsDevProfileOverrideAlone() throws Exception {
        writeAppProperties("%dev.quarkus.http.port=8081\n");

        assertEquals(8081, QuarkusProcessManager.detectHttpPort(tempDir.toString()));
    }

    @Test
    void detectHttpPortFallsBackToDefaultWhenNoFile() {
        assertEquals(QuarkusProcessManager.DEFAULT_HTTP_PORT,
                QuarkusProcessManager.detectHttpPort(tempDir.toString()));
    }

    @Test
    void detectHttpPortFallsBackToDefaultWhenPropertyAbsent() throws Exception {
        writeAppProperties("quarkus.datasource.db-kind=h2\n");

        assertEquals(QuarkusProcessManager.DEFAULT_HTTP_PORT,
                QuarkusProcessManager.detectHttpPort(tempDir.toString()));
    }

    @Test
    void detectHttpPortFallsBackToDefaultForConfigExpression() throws Exception {
        writeAppProperties("quarkus.http.port=${PORT:8080}\n");

        assertEquals(QuarkusProcessManager.DEFAULT_HTTP_PORT,
                QuarkusProcessManager.detectHttpPort(tempDir.toString()));
    }
}
