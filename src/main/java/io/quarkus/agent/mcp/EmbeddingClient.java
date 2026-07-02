package io.quarkus.agent.mcp;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EmbeddingClient {

    private static final Logger LOG = Logger.getLogger(EmbeddingClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    WebClient webClient;

    @Inject
    ContainerManager containerManager;

    public boolean isReady() {
        return containerManager.isDefaultReady();
    }

    public boolean isFailed() {
        return containerManager.isDefaultWarmupDone() && containerManager.getDefaultWarmupError() != null;
    }

    public String getFailureMessage() {
        return containerManager.getDefaultWarmupError();
    }

    public float[] embed(String text) {
        String host = containerManager.getEmbeddingHost();
        int port = containerManager.getEmbeddingPort();

        JsonObject body = new JsonObject().put("text", text);

        var httpResponse = webClient.post(port, host, "/embed")
                .sendJsonObject(body)
                .onFailure().retry().atMost(2)
                .await().atMost(TIMEOUT);

        if (httpResponse.statusCode() != 200) {
            String errorBody = httpResponse.bodyAsString();
            throw new RuntimeException(
                    "Embedding service returned HTTP " + httpResponse.statusCode() + ": " + errorBody);
        }

        JsonObject response = httpResponse.bodyAsJsonObject();
        JsonArray arr = response.getJsonArray("embedding");
        if (arr == null || arr.isEmpty()) {
            throw new RuntimeException("Embedding service returned no embedding vector");
        }

        float[] embedding = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            embedding[i] = arr.getDouble(i).floatValue();
        }
        return embedding;
    }
}
