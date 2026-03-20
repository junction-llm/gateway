package io.junction.gateway.core.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.exception.ProviderException;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaProviderEmbeddingsTest {

    private HttpServer server;
    private OllamaProvider provider;
    private final AtomicReference<String> responseBody = new AtomicReference<>("""
        {"model":"embeddinggemma","embeddings":[[0.1,0.2,0.3]],"prompt_eval_count":12}
        """);
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicReference<String> traceparentHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", this::handleEmbed);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        provider = new OllamaProvider(baseUrl(), "embeddinggemma");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedsSingleInputAndMapsUsage() {
        var request = new EmbeddingRequest("embeddinggemma", List.of("hello world"), null, null, null);

        var response = withContext(request, () -> provider.embed(request));
        var embedding = (List<?>) response.data().getFirst().embedding();

        assertEquals("list", response.object());
        assertEquals("embeddinggemma", response.model());
        assertEquals(1, response.data().size());
        assertEquals("embedding", response.data().getFirst().object());
        assertEquals(3, embedding.size());
        assertEquals(12, response.usage().promptTokens());
        assertEquals(12, response.usage().totalTokens());
        assertTrue(requestBody.get().contains("\"input\":\"hello world\""));
    }

    @Test
    void embedsBatchInputInOrder() {
        responseBody.set("""
            {"model":"embeddinggemma","embeddings":[[0.1,0.2],[0.3,0.4]],"prompt_eval_count":7}
            """);
        var request = new EmbeddingRequest("embeddinggemma", List.of("one", "two"), null, null, null);

        var response = withContext(request, () -> provider.embed(request));

        assertEquals(2, response.data().size());
        assertEquals(0, response.data().get(0).index());
        assertEquals(1, response.data().get(1).index());
        assertTrue(requestBody.get().contains("\"input\":[\"one\",\"two\"]"));
    }

    @Test
    void encodesEmbeddingsAsBase64WhenRequested() {
        var request = new EmbeddingRequest("embeddinggemma", List.of("hello world"), "base64", null, null);

        var response = withContext(request, () -> provider.embed(request));

        assertEquals("zczMPc3MTD6amZk+", response.data().getFirst().embedding());
    }

    @Test
    void surfacesHttpErrors() {
        responseStatus.set(404);
        responseBody.set("{\"error\":\"model not found\"}");
        var request = new EmbeddingRequest("missing-model", List.of("hello"), null, null, null);

        var exception = assertThrows(ProviderException.class, () -> withContext(request, () -> provider.embed(request)));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("HTTP 404"));
    }

    @Test
    void recordsTelemetryForSuccessfulEmbeddingsRequest() {
        var telemetry = new RecordingTelemetry();
        provider = new OllamaProvider(baseUrl(), "embeddinggemma", telemetry);
        var request = new EmbeddingRequest("embeddinggemma", List.of("hello world"), null, null, null);

        withContext(request, () -> provider.embed(request));

        assertEquals(List.of("ollama:embeddings:success"), telemetry.providerRequests);
    }

    @Test
    void injectsTraceparentHeaderWhenTracingIsEnabled() {
        var request = new EmbeddingRequest("embeddinggemma", List.of("hello world"), null, null, null);
        provider = new OllamaProvider(baseUrl(), "embeddinggemma", GatewayTelemetry.noop(), new TestTracing());

        withContext(request, () -> provider.embed(request));

        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", traceparentHeader.get());
    }

    private <T> T withContext(EmbeddingRequest request, ThrowingSupplier<T> supplier) {
        var context = new RequestContext.Context(
            UUID.randomUUID(),
            "test-api-key",
            request.model(),
            Instant.now()
        );

        try {
            return ScopedValue.where(RequestContext.key(), context).call(supplier::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("Unexpected checked exception in test context", e);
        }
    }

    private void handleEmbed(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        traceparentHeader.set(exchange.getRequestHeaders().getFirst("traceparent"));

        byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), response.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(response);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class RecordingTelemetry implements GatewayTelemetry {
        private final List<String> providerRequests = new ArrayList<>();

        @Override
        public void recordProviderRequest(String providerId, String operation, String outcome, long durationNanos) {
            providerRequests.add(providerId + ":" + operation + ":" + outcome);
        }
    }

    private static final class TestTracing implements GatewayTracing {
        private static final RequestContext.DistributedTrace CURRENT_TRACE =
            new RequestContext.DistributedTrace(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7"
            );

        @Override
        public ContextSnapshot capture() {
            return new ContextSnapshot() { };
        }

        @Override
        public ContextSnapshot fromTraceparent(String traceparent) {
            return new ContextSnapshot() { };
        }

        @Override
        public TraceScope startSpan(String name, ContextSnapshot parent) {
            return new TraceScope() {
                @Override
                public RequestContext.DistributedTrace trace() {
                    return CURRENT_TRACE;
                }

                @Override
                public java.util.Map<String, String> propagationHeaders() {
                    return java.util.Map.of(
                        "traceparent",
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    );
                }

                @Override
                public void tag(String key, String value) {
                }

                @Override
                public void event(String value) {
                }

                @Override
                public void error(Throwable error) {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public RequestContext.DistributedTrace currentTrace() {
            return CURRENT_TRACE;
        }
    }
}
