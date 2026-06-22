package io.quarkus.agent.mcp;

import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@Singleton
public class WebClientProducer {

    @Produces
    @Singleton
    WebClient webClient(Vertx vertx) {
        return WebClient.create(vertx, new WebClientOptions()
                .setConnectTimeout(10_000)
                .setFollowRedirects(true));
    }

    void close(@Disposes WebClient webClient) {
        webClient.close();
    }
}
