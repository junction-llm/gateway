package io.junction.gateway.starter.observability;

import com.sun.net.httpserver.HttpServer;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.starter.JunctionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderHealthRefresherTest {

    @Test
    @Timeout(5)
    void refreshOnceUpdatesRoundRobinRouterCache() throws IOException {
        var healthChecks = new AtomicInteger();
        var server = startOllamaHealthServer(healthChecks);
        try {
            var router = new RoundRobinRouter(List.of(
                new OllamaProvider("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5")
            ));
            var refresher = new ProviderHealthRefresher(router, new JunctionProperties.Health());

            refresher.refreshOnce("test");

            assertThat(healthChecks).hasValue(1);
            assertThat(router.getProviderHealthSnapshots().getFirst().healthy()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshOnceSkipsRoutersWithoutRefreshSupport() {
        var router = new Router() {
            @Override
            public io.junction.gateway.core.provider.LlmProvider route(io.junction.gateway.core.model.ChatCompletionRequest request, String preferredProvider) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.junction.gateway.core.provider.LlmProvider route(io.junction.gateway.core.model.EmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<io.junction.gateway.core.provider.LlmProvider> getProviders() {
                return List.of();
            }
        };

        new ProviderHealthRefresher(router, new JunctionProperties.Health()).refreshOnce("test");
    }

    private HttpServer startOllamaHealthServer(AtomicInteger healthChecks) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            healthChecks.incrementAndGet();
            byte[] body = "{\"models\":[]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        server.start();
        return server;
    }
}
