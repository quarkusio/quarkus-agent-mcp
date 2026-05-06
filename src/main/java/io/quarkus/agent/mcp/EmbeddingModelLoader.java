package io.quarkus.agent.mcp;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EmbeddingModelLoader {

    private static final Logger LOG = Logger.getLogger(EmbeddingModelLoader.class);

    private final CompletableFuture<EmbeddingModel> modelFuture = new CompletableFuture<>();

    void onStart(@Observes StartupEvent event) {
        Thread.ofVirtual().name("embedding-model-loader").start(() -> {
            try {
                LOG.info("Loading BGE Small EN v1.5 embedding model (384 dimensions)...");
                EmbeddingModel model = new BgeSmallEnV15QuantizedEmbeddingModel();
                LOG.info("Embedding model loaded.");
                modelFuture.complete(model);
            } catch (Exception e) {
                LOG.error("Failed to load embedding model", e);
                modelFuture.completeExceptionally(e);
            }
        });
    }

    public boolean isReady() {
        return modelFuture.isDone() && !modelFuture.isCompletedExceptionally();
    }

    public boolean isFailed() {
        return modelFuture.isCompletedExceptionally();
    }

    public EmbeddingModel getModel() {
        return modelFuture.getNow(null);
    }

    public String getFailureMessage() {
        if (!modelFuture.isCompletedExceptionally()) {
            return null;
        }
        try {
            modelFuture.join();
            return null;
        } catch (Exception e) {
            return e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        }
    }
}
