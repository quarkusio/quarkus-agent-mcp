package io.quarkus.agent.mcp;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StartupObserver {

    private static final Logger LOG = Logger.getLogger(StartupObserver.class);

    @Inject
    ContainerManager containerManager;

    void onStart(@Observes StartupEvent event) {
        LOG.infof("Quarkus Agent MCP running on Java %s (%s)",
                Runtime.version(), System.getProperty("java.vm.name"));
        containerManager.warmUpDefaultAsync();
    }
}
