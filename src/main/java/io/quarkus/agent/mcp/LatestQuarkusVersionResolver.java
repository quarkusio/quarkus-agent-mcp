package io.quarkus.agent.mcp;

import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Resolves the latest released Quarkus version from Maven Central (or a configured mirror).
 * Results are cached with a 1-hour TTL. Resolution is non-blocking: returns the cached value
 * immediately and triggers an async refresh when stale, so skill responses are never delayed
 * by network timeouts.
 */
@ApplicationScoped
public class LatestQuarkusVersionResolver {

    private static final Logger LOG = Logger.getLogger(LatestQuarkusVersionResolver.class);
    private static final long CACHE_TTL_MS = 3_600_000;
    private static final Pattern RELEASE_PATTERN = Pattern.compile("<release>([^<]+)</release>");

    private volatile String cachedVersion;
    private volatile long cacheTimestamp;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    @Inject
    WebClient webClient;

    public String resolve(String projectDir) {
        String current = cachedVersion;
        if (current != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return current;
        }
        triggerAsyncRefresh(projectDir);
        return current;
    }

    private void triggerAsyncRefresh(String projectDir) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }

        // Re-check after winning the CAS — another thread may have just completed a refresh
        if (cachedVersion != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            refreshing.set(false);
            return;
        }

        String baseUrl = SkillReader.resolveMavenRepoBaseUrl(projectDir);
        String metadataUrl = baseUrl + "/io/quarkus/quarkus-bom/maven-metadata.xml";

        webClient.getAbs(metadataUrl)
                .timeout(10_000)
                .send()
                .subscribe().with(
                        response -> {
                            try {
                                if (response.statusCode() == 200) {
                                    String version = parseRelease(response.bodyAsString());
                                    if (version != null) {
                                        cachedVersion = version;
                                        cacheTimestamp = System.currentTimeMillis();
                                        LOG.debugf("Resolved latest Quarkus version: %s", version);
                                    }
                                }
                            } finally {
                                refreshing.set(false);
                            }
                        },
                        failure -> {
                            LOG.debugf("Failed to resolve latest Quarkus version from %s: %s",
                                    metadataUrl, failure.getMessage());
                            refreshing.set(false);
                        });
    }

    static String parseRelease(String xml) {
        Matcher m = RELEASE_PATTERN.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    void invalidateCache() {
        cachedVersion = null;
        cacheTimestamp = 0;
    }
}
