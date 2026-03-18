package io.quarkus.agent.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.jboss.logging.Logger;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;

/**
 * Produces the BGE Small EN v1.5 embedding model as a CDI bean.
 * This must match the model used by chappie-docling-rag to build the pgvector index.
 */
@ApplicationScoped
public class EmbeddingModelProducer {

    private static final Logger LOG = Logger.getLogger(EmbeddingModelProducer.class);

    @Produces
    @ApplicationScoped
    EmbeddingModel embeddingModel() {
        LOG.info("Loading BGE Small EN v1.5 embedding model (384 dimensions)...");
        EmbeddingModel model = new BgeSmallEnV15QuantizedEmbeddingModel();
        LOG.info("Embedding model loaded.");
        return model;
    }
}
