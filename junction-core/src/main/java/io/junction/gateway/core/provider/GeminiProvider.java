package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.model.*;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Gatherer;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

public final class GeminiProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 100;

    private final HttpClient client;
    private final String apiKey;
    private final String model;
    private final GatewayTelemetry telemetry;
    private final GatewayTracing tracing;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Semaphore requestBulkhead;
    
    public GeminiProvider(String apiKey, String model) {
        this(apiKey, model, GatewayTelemetry.noop(), GatewayTracing.noop());
    }

    public GeminiProvider(String apiKey, String model, GatewayTelemetry telemetry) {
        this(apiKey, model, telemetry, GatewayTracing.noop());
    }

    public GeminiProvider(String apiKey, String model, GatewayTelemetry telemetry, GatewayTracing tracing) {
        this(apiKey, model, telemetry, tracing, Duration.ofSeconds(10), Duration.ofMinutes(5), DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    public GeminiProvider(String apiKey, String model, GatewayTelemetry telemetry, GatewayTracing tracing,
                          Duration connectTimeout, Duration requestTimeout) {
        this(apiKey, model, telemetry, tracing, connectTimeout, requestTimeout, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    public GeminiProvider(String apiKey, String model, GatewayTelemetry telemetry, GatewayTracing tracing,
                          Duration connectTimeout, Duration requestTimeout, int maxConcurrentRequests) {
        this.apiKey = apiKey;
        this.model = model;
        this.telemetry = telemetry != null ? telemetry : GatewayTelemetry.noop();
        this.tracing = tracing != null ? tracing : GatewayTracing.noop();
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        this.requestBulkhead = new Semaphore(maxConcurrentRequests);
        this.client = HttpClient.newBuilder()
            .connectTimeout(this.connectTimeout)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }
    
    @Override
    public String providerId() { return "gemini"; }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
    
    @Override
    public boolean isHealthy() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }
    
    @Override
    public Stream<ProviderResponse> execute(ChatCompletionRequest request) {
        var startNanos = System.nanoTime();
        var traceId = RequestContext.Context.current().traceId();
        var targetModel = request.model() != null && !request.model().isBlank() ? request.model() : model;
        var traceScope = tracing.startSpan("junction.provider.chat");
        traceScope.tag("junction.provider", providerId());
        traceScope.tag("junction.operation", "chat");
        if (targetModel != null) {
            traceScope.tag("junction.model", targetModel);
        }
        
        var geminiBody = transformToGemini(request);
        
        var uri = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/models/" + 
            targetModel + ":streamGenerateContent?key=" + apiKey
        );
        
        var httpReqBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .header("X-Trace-ID", traceId.toString())
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(geminiBody));
        traceScope.propagationHeaders().forEach(httpReqBuilder::header);
        var httpReq = httpReqBuilder.build();
            
        if (!requestBulkhead.tryAcquire()) {
            traceScope.tag("junction.outcome", "overloaded");
            telemetry.recordProviderRequest(providerId(), "chat", "overloaded", System.nanoTime() - startNanos);
            traceScope.close();
            return Stream.of(new ProviderResponse.ErrorResponse("Gemini provider concurrency limit exceeded", 503));
        }

        try {
            var response = client.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (var ignored = response.body()) {
                    // Close provider response body before returning a synthetic error stream.
                }
                traceScope.tag("junction.outcome", "http_error");
                traceScope.tag("http.status_code", Integer.toString(response.statusCode()));
                traceScope.error(new IllegalStateException("Gemini error: HTTP " + response.statusCode()));
                telemetry.recordProviderRequest(providerId(), "chat", "http_error", System.nanoTime() - startNanos);
                traceScope.close();
                requestBulkhead.release();
                return Stream.of(new ProviderResponse.ErrorResponse("Gemini error: HTTP " + response.statusCode(), response.statusCode()));
            }
            return releaseOnClose(instrumentStream(parseGeminiStream(response.body()), "chat", startNanos, traceScope));
        } catch (Exception e) {
            traceScope.tag("junction.outcome", "unexpected_error");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "unexpected_error", System.nanoTime() - startNanos);
            traceScope.close();
            requestBulkhead.release();
            return Stream.of(new ProviderResponse.ErrorResponse(e.getMessage(), 500));
        }
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        try (var traceScope = tracing.startSpan("junction.provider.embeddings")) {
            traceScope.tag("junction.provider", providerId());
            traceScope.tag("junction.operation", "embeddings");
            traceScope.tag("junction.outcome", "unsupported");
            telemetry.recordProviderRequest(providerId(), "embeddings", "unsupported", 0L);
            throw new UnsupportedOperationException("Gemini embeddings are not supported in this release.");
        }
    }
    
    private Stream<ProviderResponse> parseGeminiStream(InputStream is) {
        var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        var stream = reader.lines()
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring(6))
            .<ProviderResponse>map(json -> {
                try {
                    if (json.isBlank() || "[DONE]".equals(json)) {
                        return new ProviderResponse.GeminiResponse("", 0, 0, true);
                    }

                    var root = OBJECT_MAPPER.readTree(json);
                    
                    var text = root.path("candidates")
                        .path(0)
                        .path("content")
                        .path("parts")
                        .path(0)
                        .path("text")
                        .asText();
                        
                    var finished = root.path("candidates")
                        .path(0)
                        .path("finishReason")
                        .asText()
                        .equals("STOP");
                        
                    return new ProviderResponse.GeminiResponse(text, 0, 0, finished);
                } catch (Exception e) {
                    return new ProviderResponse.ErrorResponse("Parse error", 500);
                }
            });

        return stream.onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                log.debug("Error closing Gemini stream", e);
            }
        });
    }
    
    private String transformToGemini(ChatCompletionRequest request) {
        var contents = request.messages().stream()
            .map(m -> {
                String role;
                String content;
                switch (m.role()) {
                    case "system":
                        role = "user";
                        content = "[System] " + m.getTextContent();
                        break;
                    case "assistant":
                        role = "model";
                        content = m.getTextContent();
                        break;
                    default:
                        role = "user";
                        content = m.getTextContent();
                        break;
                }
                return Map.of(
                    "role", role,
                    "parts", List.of(Map.of("text", content))
                );
            })
            .toList();
            
        var temp = request.temperature() != null ? request.temperature() : 0.3;
        var payload = Map.of(
            "contents", contents,
            "generationConfig", Map.of("temperature", temp)
        );

        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Gemini request", e);
        }
    }
    
    private String escapeJson(String s) {
        try {
            return OBJECT_MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Gemini string content", e);
        }
    }
    
    @Override
    public List<ModelInfo> getAvailableModels() {
        if (model != null && !model.isBlank()) {
            return List.of(ModelInfo.of(model, Map.of("owned_by", "google")));
        }
        return List.of();
    }
    
    @Override
    public Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter() {
        return new io.junction.gateway.core.gatherer.OpenAIAdapterGatherer(model);
    }

    private Stream<ProviderResponse> releaseOnClose(Stream<ProviderResponse> stream) {
        var released = new AtomicBoolean(false);
        return stream.onClose(() -> {
            if (released.compareAndSet(false, true)) {
                requestBulkhead.release();
            }
        });
    }

    private Stream<ProviderResponse> instrumentStream(Stream<ProviderResponse> stream,
                                                      String operation,
                                                      long startNanos,
                                                      GatewayTracing.TraceScope traceScope) {
        var outcome = new AtomicReference<>("success");
        return stream
            .peek(response -> {
                if (response instanceof ProviderResponse.ErrorResponse) {
                    outcome.set("error");
                }
            })
            .onClose(() -> {
                traceScope.tag("junction.outcome", outcome.get());
                telemetry.recordProviderRequest(
                    providerId(),
                    operation,
                    outcome.get(),
                    System.nanoTime() - startNanos
                );
                traceScope.close();
            });
    }
}
