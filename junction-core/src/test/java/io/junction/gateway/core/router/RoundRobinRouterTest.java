package io.junction.gateway.core.router;

import com.sun.net.httpserver.HttpServer;
import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.exception.RouterException;
import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.provider.GeminiProvider;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.telemetry.GatewayTelemetry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
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

    @Test
    void routeChatCompletionsDefaultsToOllamaWhenNoPreferredProvider() throws IOException {
        var server = startOllamaHealthServer();
        try {
            var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
            var ollama = new OllamaProvider("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5");
            var router = new RoundRobinRouter(List.of(textOnly, ollama));

            var request = new ChatCompletionRequest(
                "qwen3.5",
                List.of(
                    new ChatCompletionRequest.Message(
                        "user",
                        List.of(
                            ChatCompletionRequest.ContentPart.text("Hello")
                        )
                    )
                ),
                false,
                null,
                null
            );

            var selected = router.route(request, null);
            assertEquals("ollama", selected.providerId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routeChatCompletionsRecordsTelemetry() throws IOException {
        var server = startOllamaHealthServer();
        try {
            var telemetry = new RecordingTelemetry();
            var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
            var ollama = new OllamaProvider("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5");
            var router = new RoundRobinRouter(List.of(textOnly, ollama), telemetry);

            var request = new ChatCompletionRequest(
                "qwen3.5",
                List.of(new ChatCompletionRequest.Message("user", List.of(ChatCompletionRequest.ContentPart.text("Hello")))),
                false,
                null,
                null
            );

            var selected = router.route(request);

            assertEquals("ollama", selected.providerId());
            assertEquals(List.of("chat:ollama:false"), telemetry.routeSelections);
            assertEquals(List.of("gemini:true", "ollama:true"), telemetry.healthChecks.stream().sorted().toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void routeChatCompletionsDefaultsToNonOllamaWhenOllamaUnavailable() {
        var router = new RoundRobinRouter(List.of(
            new OllamaProvider("http://127.0.0.1:1", "qwen3.5"),
            new GeminiProvider("test-api-key", "gemini-1.5-flash")
        ));

        var request = new ChatCompletionRequest(
            "qwen3.5",
            List.of(
                new ChatCompletionRequest.Message(
                    "user",
                    List.of(
                        ChatCompletionRequest.ContentPart.text("Hello")
                    )
                )
            ),
            false,
            null,
            null
        );

        var selected = router.route(request);
        assertEquals("gemini", selected.providerId());
    }

    @Test
    void routeChatCompletionsRespectsPreferredProviderOllama() throws IOException {
        var server = startOllamaHealthServer();
        try {
            var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
            var ollama = new OllamaProvider("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5");
            var router = new RoundRobinRouter(List.of(textOnly, ollama));

            var request = new ChatCompletionRequest(
                "qwen3.5",
                List.of(
                    new ChatCompletionRequest.Message(
                        "user",
                        List.of(
                            ChatCompletionRequest.ContentPart.text("Hello")
                        )
                    )
                ),
                false,
                null,
                null
            );

            var selected = router.route(request, "ollama");
            assertEquals("ollama", selected.providerId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routeChatCompletionsRespectsPreferredProviderGemini() {
        var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
        var ollama = new OllamaProvider("http://127.0.0.1:1", "qwen3.5");
        var router = new RoundRobinRouter(List.of(ollama, textOnly));
        var request = new ChatCompletionRequest(
            "qwen3.5",
            List.of(
                new ChatCompletionRequest.Message(
                    "user",
                    List.of(
                        ChatCompletionRequest.ContentPart.text("Hello")
                    )
                )
            ),
            false,
            null,
            null
        );

        var selected = router.route(request, "gemini");
        assertEquals("gemini", selected.providerId());
    }

    @Test
    void routeChatCompletionsRejectsUnknownPreferredProvider() throws IOException {
        var server = startOllamaHealthServer();
        try {
            var textOnly = new GeminiProvider("test-api-key", "gemini-1.5-flash");
            var ollama = new OllamaProvider("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5");
            var router = new RoundRobinRouter(List.of(textOnly, ollama));

            var request = new ChatCompletionRequest(
                "qwen3.5",
                List.of(
                    new ChatCompletionRequest.Message(
                        "user",
                        List.of(
                            ChatCompletionRequest.ContentPart.text("Hello")
                        )
                    )
                ),
                false,
                null,
                null
            );

            assertThrows(RouterException.class, () -> router.route(request, "openai"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void routeChatCompletionsPreferredProviderUnavailable() {
        var router = new RoundRobinRouter(List.of(
            new OllamaProvider("http://127.0.0.1:1", "qwen3.5"),
            new GeminiProvider("test-api-key", "gemini-1.5-flash")
        ));

        var request = new ChatCompletionRequest(
            "qwen3.5",
            List.of(
                new ChatCompletionRequest.Message(
                    "user",
                    List.of(
                        ChatCompletionRequest.ContentPart.text("Hello")
                    )
                )
            ),
            false,
            null,
            null
        );

        assertThrows(NoProviderAvailableException.class, () -> router.route(request, "ollama"));
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

    private static final class RecordingTelemetry implements GatewayTelemetry {
        private final List<String> routeSelections = new ArrayList<>();
        private final List<String> healthChecks = new ArrayList<>();

        @Override
        public void recordRouteSelection(String operation, String providerId, boolean preferred) {
            routeSelections.add(operation + ":" + providerId + ":" + preferred);
        }

        @Override
        public void recordProviderHealthCheck(String providerId, boolean healthy, long durationNanos) {
            healthChecks.add(providerId + ":" + healthy);
        }
    }
}
