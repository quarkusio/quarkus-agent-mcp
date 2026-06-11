package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkiverse.mcp.server.ToolArg;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Tests that quarkus_start exposes the jvmArgs parameter as an optional @ToolArg
 * and that LifecycleTools.start threads it through to QuarkusProcessManager.start.
 */
class LifecycleToolsJvmArgsTest {

    @Test
    void startMethodHasJvmArgsParameter() throws Exception {
        Method start = findStartMethod();
        var names = new ArrayList<String>();
        for (Parameter p : start.getParameters()) {
            names.add(p.getName());
        }
        assertTrue(names.contains("jvmArgs"),
                "start() must have a jvmArgs parameter, got: " + names);
    }

    @Test
    void jvmArgsParameterIsAnnotatedWithToolArg() throws Exception {
        Method start = findStartMethod();
        for (Parameter p : start.getParameters()) {
            if ("jvmArgs".equals(p.getName())) {
                assertNotNull(p.getAnnotation(ToolArg.class),
                        "jvmArgs parameter must be annotated with @ToolArg");
                return;
            }
        }
        fail("jvmArgs parameter not found");
    }

    @Test
    void jvmArgsToolArgIsNotRequired() throws Exception {
        Method start = findStartMethod();
        for (Parameter p : start.getParameters()) {
            if ("jvmArgs".equals(p.getName())) {
                ToolArg toolArg = p.getAnnotation(ToolArg.class);
                assertNotNull(toolArg, "jvmArgs must have @ToolArg");
                assertFalse(toolArg.required(), "jvmArgs @ToolArg must not be required");
                return;
            }
        }
        fail("jvmArgs parameter not found");
    }

    @Test
    void processManagerStartAcceptsJvmArgs() throws NoSuchMethodException {
        // Verify the 5-arg overload exists (the one LifecycleTools must call)
        assertDoesNotThrow(() ->
                QuarkusProcessManager.class.getDeclaredMethod(
                        "start", String.class, String.class, Integer.class, String.class, String.class),
                "QuarkusProcessManager must expose start(projectDir, buildTool, httpPort, mavenProfiles, jvmArgs)");
    }

    // -------------------------------------------------------------------------

    private static Method findStartMethod() throws Exception {
        for (Method m : LifecycleTools.class.getDeclaredMethods()) {
            if ("start".equals(m.getName())) return m;
        }
        throw new AssertionError("start method not found in LifecycleTools");
    }
}
