package io.quarkus.agent.mcp;

import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StartupObserver {

    private static final Logger LOG = Logger.getLogger(StartupObserver.class);

    private static final List<String> DEV_MODE_ONLY_TOOLS = List.of(
            "quarkus_searchTools", "quarkus_callTool", "quarkus_browser");

    @Inject
    ContainerManager containerManager;

    @Inject
    ToolManager toolManager;

    @ConfigProperty(name = "agent-mcp.mode", defaultValue = "dev")
    String mode;

    void onStart(@Observes StartupEvent event) {
        LOG.infof("Quarkus Agent MCP running on Java %s (%s) — %s mode",
                Runtime.version(), System.getProperty("java.vm.name"), mode);
        containerManager.warmUpDefaultAsync();

        if (!"dev".equals(mode)) {
            for (String toolName : DEV_MODE_ONLY_TOOLS) {
                if (toolManager.removeTool(toolName) != null) {
                    LOG.infof("Removed dev-mode-only tool: %s", toolName);
                }
            }
        }
    }
}
