package io.junction.gateway.core.router;

import com.sun.net.httpserver.HttpServer;
import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.provider.GeminiProvider;
import io.junction.gateway.core.provider.OllamaProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoundRobinRouterTest {

    @Test
    void routeEmbeddingsRejectsProvidersWithoutEmbeddingsSupport() {
        var router = new RoundRobinRouter(List.of(new GeminiProvider("test-api-key", "gemini-1.5-flash")));
        var request = new EmbeddingRequest("embeddinggemma", List.of("hello"), null, null, null);

        assertThrows(NoProviderAvailableException.class, () -> router.route(request));
    }

    @Test
    @Timeout(5)
    void routeChatCompletionsRoutesImageRequestsToImageCapableProviders() throws IOException {
        var server = startOllamaHealthServer();
        try {
            var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
            var imageCapable = new OllamaProvider("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5");
            var router = new RoundRobinRouter(List.of(textOnly, imageCapable));

            var request = new ChatCompletionRequest(
                "qwen3.5",
                List.of(
                    new ChatCompletionRequest.Message(
                        "user",
                        List.of(
                            ChatCompletionRequest.ContentPart.imageUrl("https://example.com/image.png")
                        )
                    )
                ),
                false,
                null,
                null
            );

            var selected = router.route(request);

            assertEquals("ollama", selected.providerId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routeChatCompletionsRejectsImageRequestsWhenNoImageProviderAvailable() {
        var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
        var router = new RoundRobinRouter(List.of(textOnly));

        var request = new ChatCompletionRequest(
            "qwen3.5",
            List.of(
                new ChatCompletionRequest.Message(
                    "user",
                    List.of(
                        ChatCompletionRequest.ContentPart.imageUrl("https://example.com/image.png")
                    )
                )
            ),
            false,
            null,
            null
        );

        assertThrows(NoProviderAvailableException.class, () -> router.route(request));
    }

    private HttpServer startOllamaHealthServer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
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
