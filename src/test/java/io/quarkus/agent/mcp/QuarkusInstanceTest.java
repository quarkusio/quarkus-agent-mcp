package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QuarkusInstanceTest {

    private ExecutorService executor = Executors.newCachedThreadPool();
    private Process process;

    @AfterEach
    void cleanup() {
        executor.shutdownNow();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    @Test
    void initialStatusIsStarting() throws Exception {
        process = new ProcessBuilder("sleep", "10").start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        assertEquals(QuarkusInstance.Status.STARTING, instance.getStatus());
        assertEquals(-1, instance.getHttpPort());
        assertEquals("/test/project", instance.getProjectDir());
    }

    @Test
    void detectsPortFromListeningOnLog() throws Exception {
        // Use a process that outputs a "Listening on:" line
        process = new ProcessBuilder("bash", "-c",
                "echo 'Listening on: http://localhost:8080' && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        // Wait for the stream reader to process the output
        Thread.sleep(500);

        assertEquals(8080, instance.getHttpPort());
        assertEquals(QuarkusInstance.Status.RUNNING, instance.getStatus());
    }

    @Test
    void detectsPortFromHttpsUrl() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "echo 'Listening on: https://localhost:8443' && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        assertEquals(8443, instance.getHttpPort());
    }

    @Test
    void detectsRunningFromInstalledFeatures() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "echo 'installed features: [cdi, rest]' && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        assertEquals(QuarkusInstance.Status.RUNNING, instance.getStatus());
        // Port should still be -1 since no "Listening on:" line
        assertEquals(-1, instance.getHttpPort());
    }

    @Test
    void detectsCrashOnProcessExit() throws Exception {
        process = new ProcessBuilder("bash", "-c", "echo 'starting' && exit 1")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        // Wait for process to exit and monitor to detect it
        Thread.sleep(500);

        assertEquals(QuarkusInstance.Status.CRASHED, instance.getStatus());
        assertFalse(instance.isAlive());
    }

    @Test
    void stopSetsStatusToStopped() throws Exception {
        process = new ProcessBuilder("sleep", "30").start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        instance.stop();

        assertEquals(QuarkusInstance.Status.STOPPED, instance.getStatus());
        // Process should be dead after stop
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }

    @Test
    void logsAreCapturedInRingBuffer() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "for i in $(seq 1 10); do echo \"line $i\"; done && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        String logs = instance.getRecentLogs(5);
        assertTrue(logs.contains("line 10"));
        assertTrue(logs.contains("line 6"));
        assertFalse(logs.contains("line 5"));
    }

    @Test
    void getRecentLogsReturnsEmptyForNoLogs() throws Exception {
        process = new ProcessBuilder("sleep", "10").start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        assertEquals("", instance.getRecentLogs(50));
    }

    @Test
    void portParsesNonStandardPorts() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "echo 'Listening on: http://0.0.0.0:9090' && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        assertEquals(9090, instance.getHttpPort());
    }

    @Test
    void portIgnoresMalformedUrl() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "echo 'Listening on: not-a-url' && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        assertEquals(-1, instance.getHttpPort());
    }

    @Test
    void portHandlesUrlWithTrailingText() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "echo 'Listening on: http://localhost:8080 (some extra text)' && sleep 5")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        assertEquals(8080, instance.getHttpPort());
    }

    @Test
    void restartResetsPortAndStatus() throws Exception {
        process = new ProcessBuilder("bash", "-c",
                "echo 'Listening on: http://localhost:8080' && cat")
                .start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);
        assertEquals(8080, instance.getHttpPort());
        assertEquals(QuarkusInstance.Status.RUNNING, instance.getStatus());

        instance.restart();

        assertEquals(QuarkusInstance.Status.STARTING, instance.getStatus());
        assertEquals(-1, instance.getHttpPort());
    }

    @Test
    void restartThrowsIfProcessDead() throws Exception {
        process = new ProcessBuilder("bash", "-c", "exit 0").start();
        QuarkusInstance instance = new QuarkusInstance("/test/project", process, executor);

        Thread.sleep(500);

        assertThrows(IllegalStateException.class, instance::restart);
    }
}
