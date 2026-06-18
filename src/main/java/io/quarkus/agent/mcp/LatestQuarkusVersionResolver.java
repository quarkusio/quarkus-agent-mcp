package io.quarkus.agent.mcp;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Resolves the latest released Quarkus version from Maven Central (or a configured mirror).
 * Results are cached with a 1-hour TTL to avoid repeated HTTP calls.
 */
public final class LatestQuarkusVersionResolver {

    private static final Logger LOG = Logger.getLogger(LatestQuarkusVersionResolver.class);
    private static final long CACHE_TTL_MS = 3_600_000;
    private static final Pattern RELEASE_PATTERN = Pattern.compile("<release>([^<]+)</release>");

    private static volatile String cachedVersion;
    private static volatile long cacheTimestamp;

    private LatestQuarkusVersionResolver() {
    }

    public static String resolve(String projectDir) {
        if (cachedVersion != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cachedVersion;
        }
        return doResolve(projectDir);
    }

    private static synchronized String doResolve(String projectDir) {
        if (cachedVersion != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cachedVersion;
        }

        String baseUrl = SkillReader.resolveMavenRepoBaseUrl(projectDir);
        String metadataUrl = baseUrl + "/io/quarkus/quarkus-bom/maven-metadata.xml";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metadataUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClientProvider.getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String version = parseRelease(response.body());
                if (version != null) {
                    cachedVersion = version;
                    cacheTimestamp = System.currentTimeMillis();
                    LOG.debugf("Resolved latest Quarkus version: %s", version);
                    return version;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to resolve latest Quarkus version from %s: %s", metadataUrl, e.getMessage());
        }
        return cachedVersion;
    }

    static String parseRelease(String xml) {
        Matcher m = RELEASE_PATTERN.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    static void invalidateCache() {
        cachedVersion = null;
        cacheTimestamp = 0;
    }
}
