package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenReactorTest {

    @TempDir
    Path tempDir;

    private static final String ROOT_POM = """
            <project>
              <properties>
                <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
                <quarkus.platform.version>3.21.2</quarkus.platform.version>
              </properties>
              <modules>
                <module>lib</module>
                <module>app</module>
              </modules>
            </project>
            """;

    private static final String APP_POM = """
            <project>
              <build>
                <plugins>
                  <plugin>
                    <groupId>${quarkus.platform.group-id}</groupId>
                    <artifactId>quarkus-maven-plugin</artifactId>
                    <version>${quarkus.platform.version}</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private Path reactor() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT_POM);
        Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(tempDir.resolve("lib/pom.xml"), "<project/>");
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/pom.xml"), APP_POM);
        return tempDir.resolve("app");
    }

    @Test
    void findsReactorRootOfModule() throws Exception {
        Path app = reactor();
        assertEquals(Optional.of(tempDir.toRealPath()), MavenReactor.findRoot(app));
    }

    @Test
    void findsOutermostRootOfNestedModule() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modules><module>apps</module></modules></project>");
        Files.createDirectories(tempDir.resolve("apps/app"));
        Files.writeString(tempDir.resolve("apps/pom.xml"), "<project><modules><module>app</module></modules></project>");
        Files.writeString(tempDir.resolve("apps/app/pom.xml"), APP_POM);
        assertEquals(Optional.of(tempDir.toRealPath()), MavenReactor.findRoot(tempDir.resolve("apps/app")));
    }

    @Test
    void noRootForStandaloneProject() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), APP_POM);
        assertTrue(MavenReactor.findRoot(tempDir).isEmpty());
    }

    @Test
    void noRootWhenParentDoesNotListModule() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modules><module>other</module></modules></project>");
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/pom.xml"), APP_POM);
        assertTrue(MavenReactor.findRoot(tempDir.resolve("app")).isEmpty());
    }

    @Test
    void devGoalUsesPluginCoordinatesResolvedFromParentProperties() throws Exception {
        Path app = reactor();
        assertEquals("io.quarkus.platform:quarkus-maven-plugin:3.21.2:dev",
                MavenReactor.devGoal(app, tempDir));
    }

    @Test
    void processBuilderStartsModuleFromReactorRoot() throws Exception {
        Path app = reactor();
        QuarkusProcessManager manager = new QuarkusProcessManager();
        for (String fieldName : new String[] { "gradleCmd", "appLogEnabled", "configExtraArgs" }) {
            Field f = QuarkusProcessManager.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(manager, Optional.empty());
        }
        Field modeField = QuarkusProcessManager.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.set(manager, "dev");
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh");

        Method m = QuarkusProcessManager.class.getDeclaredMethod(
                "createProcessBuilder", String.class, String.class, Integer.class, String.class, String.class);
        m.setAccessible(true);
        ProcessBuilder pb = (ProcessBuilder) m.invoke(manager, app.toRealPath().toString(), "maven",
                (Integer) null, (String) null, (String) null);

        assertEquals(tempDir.toRealPath().toFile(), pb.directory());
        List<String> cmd = pb.command();
        assertEquals(tempDir.toRealPath().resolve("mvnw").toString(), cmd.get(0));
        assertEquals("io.quarkus.platform:quarkus-maven-plugin:3.21.2:dev", cmd.get(1));
        int pl = cmd.indexOf("-pl");
        assertTrue(pl > 0);
        assertEquals("app", cmd.get(pl + 1));
        assertTrue(cmd.contains("-am"));
    }

    @Test
    void wrapperIsFoundInParentBuildDirectory() throws Exception {
        Path app = reactor();
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh");
        assertEquals(tempDir.resolve("mvnw").toFile().getAbsoluteFile(),
                ProcessUtils.findWrapper(app.toFile(), "mvnw"));
    }

    @Test
    void wrapperSearchStopsAtDirectoryWithoutBuildFile() throws Exception {
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh");
        Path project = tempDir.resolve("container/project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        File found = ProcessUtils.findWrapper(project.toFile(), "mvnw");
        assertNull(found);
    }
}
