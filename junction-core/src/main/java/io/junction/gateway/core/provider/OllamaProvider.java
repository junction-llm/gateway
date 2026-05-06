package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.exception.ProviderException;
import io.junction.gateway.core.model.*;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Gatherer;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OllamaProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private static final Logger payloadLog = LoggerFactory.getLogger("io.junction.gateway.payload.ollama");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int CONSOLE_MESSAGE_PREVIEW_LIMIT = 300;
    private static final int DEFAULT_MAX_REMOTE_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int REMOTE_IMAGE_READ_BUFFER_BYTES = 8192;
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 100;
    private final HttpClient client;
    private final String baseUrl;
    private final String defaultModel;
    private final GatewayTelemetry telemetry;
    private final GatewayTracing tracing;
    private final int maxRemoteImageBytes;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Semaphore requestBulkhead;
    private final boolean allowPrivateRemoteImageUrls;
    
    public OllamaProvider(String baseUrl, String defaultModel) {
        this(baseUrl, defaultModel, GatewayTelemetry.noop(), GatewayTracing.noop());
    }

    public OllamaProvider(String baseUrl, String defaultModel, GatewayTelemetry telemetry) {
        this(baseUrl, defaultModel, telemetry, GatewayTracing.noop());
    }

    public OllamaProvider(String baseUrl, String defaultModel, GatewayTelemetry telemetry, GatewayTracing tracing) {
        this(baseUrl, defaultModel, telemetry, tracing, DEFAULT_MAX_REMOTE_IMAGE_BYTES);
    }

    public OllamaProvider(String baseUrl, String defaultModel, GatewayTelemetry telemetry, GatewayTracing tracing, int maxRemoteImageBytes) {
        this(baseUrl, defaultModel, telemetry, tracing, maxRemoteImageBytes, Duration.ofSeconds(10), Duration.ofMinutes(5), DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    public OllamaProvider(String baseUrl, String defaultModel, GatewayTelemetry telemetry, GatewayTracing tracing,
                          int maxRemoteImageBytes, Duration connectTimeout, Duration requestTimeout) {
        this(baseUrl, defaultModel, telemetry, tracing, maxRemoteImageBytes, connectTimeout, requestTimeout, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    public OllamaProvider(String baseUrl, String defaultModel, GatewayTelemetry telemetry, GatewayTracing tracing,
                          int maxRemoteImageBytes, Duration connectTimeout, Duration requestTimeout, int maxConcurrentRequests) {
        this(baseUrl, defaultModel, telemetry, tracing, maxRemoteImageBytes, connectTimeout, requestTimeout, maxConcurrentRequests, false);
    }

    public OllamaProvider(String baseUrl, String defaultModel, GatewayTelemetry telemetry, GatewayTracing tracing,
                          int maxRemoteImageBytes, Duration connectTimeout, Duration requestTimeout, int maxConcurrentRequests,
                          boolean allowPrivateRemoteImageUrls) {
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.telemetry = telemetry != null ? telemetry : GatewayTelemetry.noop();
        this.tracing = tracing != null ? tracing : GatewayTracing.noop();
        if (maxRemoteImageBytes <= 0) {
            throw new IllegalArgumentException("maxRemoteImageBytes must be positive");
        }
        this.maxRemoteImageBytes = maxRemoteImageBytes;
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        this.requestBulkhead = new Semaphore(maxConcurrentRequests);
        this.allowPrivateRemoteImageUrls = allowPrivateRemoteImageUrls;

        this.client = HttpClient.newBuilder()
            .connectTimeout(this.connectTimeout)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }
    
    @Override
    public String providerId() { return "ollama"; }
    
    @Override
    public boolean isHealthy() {
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }
    
    @Override
    public boolean supportsImageInputs() {
        return true;
    }
    
    @Override
    public Stream<ProviderResponse> execute(ChatCompletionRequest request) {
        var startNanos = System.nanoTime();
        var traceId = RequestContext.Context.current().traceId();
        var traceScope = tracing.startSpan("junction.provider.chat");
        
        var model = request.model() != null ? request.model() : defaultModel;
        var messagesPayload = formatMessages(request.messages());
        var stream = request.stream();
        var temp = request.temperature() != null ? request.temperature() : 0.3;
        
        var hasImageInputs = request.messages() != null
            && request.messages().stream().anyMatch(ChatCompletionRequest.Message::hasImageParts);

        traceScope.tag("junction.provider", providerId());
        traceScope.tag("junction.operation", "chat");
        traceScope.tag("junction.model", model);
        traceScope.tag("junction.stream", Boolean.toString(stream));
        
        log.info("[{}] OllamaProvider executing request: model={}, stream={}, temp={}, image_inputs={}", 
                traceId, model, stream, temp, hasImageInputs);
        
        var requestBody = Map.<String, Object>of(
            "model", model,
            "messages", messagesPayload,
            "stream", stream,
            "options", Map.of("temperature", temp)
        );
        
        String json;
        try {
            json = serializeRequestBody(requestBody);
        } catch (ProviderException e) {
            traceScope.tag("junction.outcome", "serialize_error");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "serialize_error", System.nanoTime() - startNanos);
            traceScope.close();
            return Stream.of(new ProviderResponse.ErrorResponse(e.getMessage(), e.getCode()));
        }
        
        if (log.isDebugEnabled()) {
            log.debug("[{}] Sending request to Ollama at {} (messages={}, image_inputs={})",
                traceId, baseUrl, messagesPayload.size(), hasImageInputs);
            log.debug("[{}] Request message preview: {}", traceId, buildDebugMessages(request.messages(), true));
        }

        if (payloadLog.isDebugEnabled()) {
            payloadLog.debug("[{}] Request messages (text-only, image payload omitted): {}", traceId,
                buildDebugMessages(request.messages(), false));
        }
            
        log.debug("[{}] Request target model: {}", traceId, model);
        
        var httpReqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("X-Trace-ID", traceId.toString())
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(json));
        traceScope.propagationHeaders().forEach(httpReqBuilder::header);
        var httpReq = httpReqBuilder.build();

        if (!requestBulkhead.tryAcquire()) {
            traceScope.tag("junction.outcome", "overloaded");
            telemetry.recordProviderRequest(providerId(), "chat", "overloaded", System.nanoTime() - startNanos);
            traceScope.close();
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama provider concurrency limit exceeded", 503));
        }

        try {
            log.debug("[{}] Sending request to Ollama at: {}", traceId, baseUrl);
            var response = client.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            
            if (response.statusCode() != 200) {
                log.error("[{}] Ollama returned HTTP error: {}", traceId, response.statusCode());
                String errorBody;
                try (var body = response.body()) {
                    errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("[{}] Error response body: {}", traceId, errorBody);
                traceScope.tag("junction.outcome", "http_error");
                traceScope.tag("http.status_code", Integer.toString(response.statusCode()));
                traceScope.error(new ProviderException(
                    "Ollama error: HTTP " + response.statusCode() + " - " + errorBody,
                    response.statusCode()
                ));
                telemetry.recordProviderRequest(providerId(), "chat", "http_error", System.nanoTime() - startNanos);
                traceScope.close();
                requestBulkhead.release();
                return Stream.of(new ProviderResponse.ErrorResponse(
                    "Ollama error: HTTP " + response.statusCode() + " - " + errorBody, 
                    response.statusCode()));
            }
            
            log.debug("[{}] Received successful response from Ollama, parsing NDJSON stream", traceId);
            return releaseOnClose(instrumentStream(parseNdJson(response.body(), traceId), "chat", startNanos, traceScope));
            
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("[{}] Timeout calling Ollama: {}", traceId, e.getMessage());
            traceScope.tag("junction.outcome", "timeout");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "timeout", System.nanoTime() - startNanos);
            traceScope.close();
            requestBulkhead.release();
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama timeout: " + e.getMessage(), 504));
        } catch (java.io.IOException e) {
            log.error("[{}] IO error calling Ollama: {}", traceId, e.getMessage());
            traceScope.tag("junction.outcome", "io_error");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "io_error", System.nanoTime() - startNanos);
            traceScope.close();
            requestBulkhead.release();
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama connection error: " + e.getMessage(), 502));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[{}] Request interrupted: {}", traceId, e.getMessage());
            traceScope.tag("junction.outcome", "interrupted");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "interrupted", System.nanoTime() - startNanos);
            traceScope.close();
            requestBulkhead.release();
            return Stream.of(new ProviderResponse.ErrorResponse("Request interrupted", 500));
        } catch (ProviderException e) {
            log.error("[{}] Invalid Ollama request: {}", traceId, e.getMessage());
            traceScope.tag("junction.outcome", "provider_error");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "provider_error", System.nanoTime() - startNanos);
            traceScope.close();
            requestBulkhead.release();
            return Stream.of(new ProviderResponse.ErrorResponse(e.getMessage(), e.getCode()));
        } catch (Exception e) {
            log.error("[{}] Unexpected error calling Ollama: {}", traceId, e.getMessage(), e);
            traceScope.tag("junction.outcome", "unexpected_error");
            traceScope.error(e);
            telemetry.recordProviderRequest(providerId(), "chat", "unexpected_error", System.nanoTime() - startNanos);
            traceScope.close();
            requestBulkhead.release();
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama error: " + e.getMessage(), 500));
        }
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        var startNanos = System.nanoTime();
        var traceId = RequestContext.Context.current().traceId();
        var model = request.model();

        try (var traceScope = tracing.startSpan("junction.provider.embeddings")) {
            traceScope.tag("junction.provider", providerId());
            traceScope.tag("junction.operation", "embeddings");
            if (model != null) {
                traceScope.tag("junction.model", model);
            }

            if (model == null || model.isBlank()) {
                traceScope.tag("junction.outcome", "invalid_request");
                telemetry.recordProviderRequest(providerId(), "embeddings", "invalid_request", System.nanoTime() - startNanos);
                throw new ProviderException("Embeddings request requires a model.", 400);
            }

            var httpReqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .header("Content-Type", "application/json")
                .header("X-Trace-ID", traceId.toString())
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(serializeEmbeddingRequest(request)));
            traceScope.propagationHeaders().forEach(httpReqBuilder::header);
            var httpReq = httpReqBuilder.build();

            if (!requestBulkhead.tryAcquire()) {
                traceScope.tag("junction.outcome", "overloaded");
                telemetry.recordProviderRequest(providerId(), "embeddings", "overloaded", System.nanoTime() - startNanos);
                throw new ProviderException("Ollama provider concurrency limit exceeded", 503);
            }

            try {
                log.info("[{}] OllamaProvider embedding request: model={}, inputs={}",
                    traceId, model, request.input().size());

                var response = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    traceScope.tag("junction.outcome", "http_error");
                    traceScope.tag("http.status_code", Integer.toString(response.statusCode()));
                    traceScope.error(new ProviderException(
                        "Ollama embeddings error: HTTP " + response.statusCode() + " - " + response.body(),
                        response.statusCode()
                    ));
                    telemetry.recordProviderRequest(providerId(), "embeddings", "http_error", System.nanoTime() - startNanos);
                    throw new ProviderException(
                        "Ollama embeddings error: HTTP " + response.statusCode() + " - " + response.body(),
                        response.statusCode()
                    );
                }

                var embeddingResponse = parseEmbeddingResponse(response.body(), model, request.encodingFormat(), traceId);
                traceScope.tag("junction.outcome", "success");
                telemetry.recordProviderRequest(providerId(), "embeddings", "success", System.nanoTime() - startNanos);
                return embeddingResponse;
            } catch (java.net.http.HttpTimeoutException e) {
                traceScope.tag("junction.outcome", "timeout");
                traceScope.error(e);
                telemetry.recordProviderRequest(providerId(), "embeddings", "timeout", System.nanoTime() - startNanos);
                throw new ProviderException("Ollama embeddings timeout: " + e.getMessage(), 504);
            } catch (java.io.IOException e) {
                traceScope.tag("junction.outcome", "io_error");
                traceScope.error(e);
                telemetry.recordProviderRequest(providerId(), "embeddings", "io_error", System.nanoTime() - startNanos);
                throw new ProviderException("Ollama embeddings connection error: " + e.getMessage(), 502);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                traceScope.tag("junction.outcome", "interrupted");
                traceScope.error(e);
                telemetry.recordProviderRequest(providerId(), "embeddings", "interrupted", System.nanoTime() - startNanos);
                throw new ProviderException("Embeddings request interrupted", 500);
            } catch (ProviderException e) {
                traceScope.tag("junction.outcome", "provider_error");
                traceScope.error(e);
                telemetry.recordProviderRequest(providerId(), "embeddings", "provider_error", System.nanoTime() - startNanos);
                throw e;
            } finally {
                requestBulkhead.release();
            }
        }
    }
    
    private String serializeRequestBody(Map<String, Object> requestBody) {
        try {
            return OBJECT_MAPPER.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new ProviderException("Failed to serialize Ollama request", 500);
        }
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

    private Stream<ProviderResponse> releaseOnClose(Stream<ProviderResponse> stream) {
        var released = new AtomicBoolean(false);
        return stream.onClose(() -> {
            if (released.compareAndSet(false, true)) {
                requestBulkhead.release();
            }
        });
    }

    private String buildDebugMessages(List<ChatCompletionRequest.Message> messages, boolean truncate) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }

        return messages.stream()
            .map(message -> describeMessageForLog(message, truncate))
            .toList()
            .toString();
    }
    
    private String describeMessageForLog(ChatCompletionRequest.Message message, boolean truncate) {
        if (message == null) {
            return "[message=<null>]";
        }
        
        var role = message.role() != null ? message.role() : "<unknown>";
        var content = message.getTextContent();
        if (content == null) {
            content = "";
        }

        content = sanitizeText(content, truncate ? CONSOLE_MESSAGE_PREVIEW_LIMIT : Integer.MAX_VALUE);
        var imageCount = message.imageUrls().size();
        var imageInfo = imageCount > 0 ? ", images=" + imageCount + " (omitted)" : "";
        return "[role=%s, content=%s%s]".formatted(role, content.isBlank() ? "<empty>" : content, imageInfo);
    }
    
    private String sanitizeText(String text, int maxLength) {
        var oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (maxLength == Integer.MAX_VALUE || oneLine.length() <= maxLength) {
            return oneLine;
        }

        return oneLine.substring(0, maxLength) + "...";
    }

    private Stream<ProviderResponse> parseNdJson(InputStream is, java.util.UUID traceId) {
        final java.util.concurrent.atomic.AtomicInteger chunkCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        
        var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        var stream = reader
            .lines()
            .map(line -> {
                try {
                    var mapper = new tools.jackson.databind.ObjectMapper();
                    var node = mapper.readTree(line);
                    
                    var content = node.path("message").path("content").asText("");
                    var thinking = node.path("message").path("thinking").asText("");
                    var model = node.path("model").asText();
                    var done = node.path("done").asBoolean();
                    
                    if (node.has("error")) {
                        var error = node.path("error").asText();
                        log.error("[{}] Ollama returned error: {}", traceId, error);
                        return (ProviderResponse) new ProviderResponse.ErrorResponse("Ollama error: " + error, 500);
                    }
                    
                    List<ProviderResponse.ToolCall> toolCalls = null;
                    var toolCallsNode = node.path("message").path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        toolCalls = new java.util.ArrayList<>();
                        for (var tcNode : toolCallsNode) {
                            var id = tcNode.path("id").asText();
                            var funcNode = tcNode.path("function");
                            var funcName = funcNode.path("name").asText();
                            
                            Integer index = null;
                            if (funcNode.has("index") && !funcNode.path("index").isNull()) {
                                index = funcNode.path("index").asInt();
                            }
                            
                            var argsNode = funcNode.path("arguments");
                            String funcArgs;
                            if (argsNode.isObject()) {
                                funcArgs = argsNode.toString();
                            } else {
                                funcArgs = argsNode.asText();
                            }
                            
                            toolCalls.add(new ProviderResponse.ToolCall(
                                id,
                                index,
                                new ProviderResponse.FunctionCall(funcName, funcArgs)
                            ));
                        }
                    }
                    
                    ProviderResponse.Usage usage = null;
                    if (node.has("prompt_eval_count") || node.has("eval_count")) {
                        int promptTokens = node.path("prompt_eval_count").asInt(0);
                        int completionTokens = node.path("eval_count").asInt(0);
                        int totalTokens = promptTokens + completionTokens;
                        usage = new ProviderResponse.Usage(promptTokens, completionTokens, totalTokens);
                        log.debug("[{}] Extracted usage - prompt_tokens: {}, completion_tokens: {}, total_tokens: {}", 
                            traceId, promptTokens, completionTokens, totalTokens);
                    }
                    
                    int currentChunk = chunkCounter.incrementAndGet();
                    boolean hasContent = !content.isEmpty();
                    boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
                    boolean hasThinking = !thinking.isEmpty();
                    
                    if (currentChunk == 1 || currentChunk % 60 == 0 || done) {
                        if (hasContent || hasToolCalls) {
                            log.debug("[{}] Received chunk #{} - model: {}, content length: {}, toolCalls: {}, done: {}", 
                                traceId, currentChunk, model, content.length(), toolCalls != null ? toolCalls.size() : 0, done);
                        } else if (hasThinking) {
                            log.debug("[{}] Received thinking chunk #{} - model: {}, thinking length: {}, done: {}",
                                traceId, currentChunk, model, thinking.length(), done);
                        } else {
                            log.debug("[{}] Received empty chunk #{} - model: {}, done: {}", traceId, currentChunk, model, done);
                        }
                    }
                    
                    if (done) {
                        log.info("[{}] Ollama stream marked as complete", traceId);
                    }
                    
                    return (ProviderResponse) new ProviderResponse.OllamaResponse(content, thinking, model, done, toolCalls, usage);
                    
                } catch (Exception e) {
                    log.error("[{}] Parse error for line: {}", traceId, line, e);
                    return (ProviderResponse) new ProviderResponse.ErrorResponse("Parse error: " + line, 500);
                }
            });
        
        return stream.onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                log.debug("[{}] Error closing Ollama NDJSON stream", traceId, e);
            }
            log.info("[{}] NDJSON stream closed", traceId);
        });
    }
    
    @Override
    public List<ModelInfo> getAvailableModels() {
        try (var traceScope = tracing.startSpan("junction.provider.models")) {
            traceScope.tag("junction.provider", providerId());
            traceScope.tag("junction.operation", "models");
            var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (resp.statusCode() != 200) {
                traceScope.tag("junction.outcome", "http_error");
                traceScope.tag("http.status_code", Integer.toString(resp.statusCode()));
                log.warn("Failed to fetch models from Ollama: HTTP {}", resp.statusCode());
                return List.of();
            }
            var rootNode = OBJECT_MAPPER.readTree(resp.body());
            var modelsNode = rootNode.path("models");
            
            if (!modelsNode.isArray()) {
                log.warn("Ollama /api/tags response missing 'models' array");
                return List.of();
            }
            
            var result = new ArrayList<ModelInfo>();
            for (var modelNode : modelsNode) {
                var modelName = modelNode.path("name").asText(null);
                if (modelName != null && !modelName.isBlank()) {
                    result.add(ModelInfo.of(modelName, Map.of("owned_by", "ollama")));
                }
            }
            
            traceScope.tag("junction.outcome", "success");
            log.info("Fetched {} models from Ollama at {}", result.size(), baseUrl);
            return result;
            
        } catch (java.net.http.HttpTimeoutException e) {
            try (var traceScope = tracing.startSpan("junction.provider.models.error")) {
                traceScope.tag("junction.provider", providerId());
                traceScope.tag("junction.operation", "models");
                traceScope.tag("junction.outcome", "timeout");
                traceScope.error(e);
            }
            log.warn("Timeout fetching models from Ollama: {}", e.getMessage());
            return List.of();
        } catch (Exception e) {
            try (var traceScope = tracing.startSpan("junction.provider.models.error")) {
                traceScope.tag("junction.provider", providerId());
                traceScope.tag("junction.operation", "models");
                traceScope.tag("junction.outcome", "error");
                traceScope.error(e);
            }
            log.error("Error fetching models from Ollama: {}", e.getMessage(), e);
            return List.of();
        }
    }
    
    @Override
    public Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter() {
        return new io.junction.gateway.core.gatherer.OpenAIAdapterGatherer(defaultModel);
    }

    private String serializeEmbeddingRequest(EmbeddingRequest request) {
        Object inputPayload = request.input().size() == 1
            ? request.input().get(0)
            : request.input();

        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                "model", request.model(),
                "input", inputPayload
            ));
        } catch (Exception e) {
            throw new ProviderException("Failed to serialize Ollama embeddings request", 500);
        }
    }

    private EmbeddingResponse parseEmbeddingResponse(String body,
                                                     String requestedModel,
                                                     String encodingFormat,
                                                     java.util.UUID traceId) {
        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(body);
            JsonNode embeddingsNode = rootNode.path("embeddings");
            if (!embeddingsNode.isArray()) {
                throw new ProviderException("Ollama embeddings response missing 'embeddings' array.", 502);
            }

            List<List<Double>> embeddings = new ArrayList<>();
            for (JsonNode embeddingNode : embeddingsNode) {
                if (!embeddingNode.isArray()) {
                    throw new ProviderException("Ollama returned an invalid embedding vector.", 502);
                }

                List<Double> vector = new ArrayList<>();
                for (JsonNode valueNode : embeddingNode) {
                    if (!valueNode.isNumber()) {
                        throw new ProviderException("Ollama returned a non-numeric embedding value.", 502);
                    }
                    vector.add(valueNode.asDouble());
                }
                embeddings.add(List.copyOf(vector));
            }

            int promptTokens = rootNode.path("prompt_eval_count").asInt(0);
            String responseModel = rootNode.path("model").asText(requestedModel);

            log.debug("[{}] Parsed {} embedding vectors from Ollama", traceId, embeddings.size());
            return EmbeddingResponse.of(responseModel, embeddings, promptTokens, encodingFormat);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("Failed to parse Ollama embeddings response: " + e.getMessage(), 502);
        }
    }
    
    private List<Map<String, Object>> formatMessages(List<ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        return messages.stream()
            .map(message -> {
                Map<String, Object> mappedMessage = new HashMap<>();
                mappedMessage.put("role", message.role());
                mappedMessage.put("content", message.getTextContent());
                
                List<String> imagePayloads = message.imageUrls().stream()
                    .map(this::normalizeImageInput)
                    .toList();
                
                if (!imagePayloads.isEmpty()) {
                    mappedMessage.put("images", imagePayloads);
                }
                
                return mappedMessage;
            })
            .toList();
    }
    
    private String normalizeImageInput(String imageSource) {
        if (imageSource == null || imageSource.isBlank()) {
            throw new ProviderException("Image source cannot be blank.", 400);
        }

        if (imageSource.startsWith("data:")) {
            return normalizeDataUrlImage(imageSource);
        }
        
        if (imageSource.startsWith("http://") || imageSource.startsWith("https://")) {
            return loadImageFromUrl(imageSource);
        }

        throw new ProviderException("Unsupported image source scheme: " + imageSource, 400);
    }
    
    private String normalizeDataUrlImage(String dataUrl) {
        var commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0 || commaIndex == dataUrl.length() - 1) {
            throw new ProviderException("Invalid data URL image format: missing base64 payload.", 400);
        }
        
        var metadata = dataUrl.substring(0, commaIndex).toLowerCase();
        var base64Payload = dataUrl.substring(commaIndex + 1);
        
        if (!metadata.contains(";base64")) {
            throw new ProviderException("Only base64 data URLs are supported for images.", 400);
        }
        
        try {
            Base64.getDecoder().decode(base64Payload);
            return base64Payload;
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Invalid base64 payload in image data URL.", 400);
        }
    }
    
    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private String loadImageFromUrl(String imageUrl) {
        try {
            var imageUri = URI.create(imageUrl);
            validateRemoteImageUri(imageUri);
            var request = HttpRequest.newBuilder()
                .uri(imageUri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ProviderException("Failed to load image from URL: HTTP " + response.statusCode(), 400);
                }

                validateRemoteImageHeaders(response);
                var imageBytes = readRemoteImageBody(body);
                if (imageBytes.length == 0) {
                    throw new ProviderException("Image URL returned no content.", 400);
                }

                return Base64.getEncoder().encodeToString(imageBytes);
            }
        } catch (ProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while loading image URL.", 500);
        } catch (Exception e) {
            throw new ProviderException("Failed to load image from URL: " + e.getMessage(), 400);
        }
    }

    private void validateRemoteImageUri(URI imageUri) throws IOException {
        var scheme = imageUri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ProviderException("Remote image URL must use http or https.", 400);
        }

        var host = imageUri.getHost();
        if (host == null || host.isBlank()) {
            throw new ProviderException("Remote image URL must include a host.", 400);
        }

        if (allowPrivateRemoteImageUrls) {
            return;
        }

        for (var address : InetAddress.getAllByName(host)) {
            if (isBlockedRemoteImageAddress(address)) {
                throw new ProviderException("Remote image URL host resolves to a private or local network address.", 400);
            }
        }
    }

    private boolean isBlockedRemoteImageAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }

        var bytes = address.getAddress();
        if (bytes.length == 4) {
            var first = bytes[0] & 0xff;
            var second = bytes[1] & 0xff;
            return (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 192 && second == 0)
                || (first == 198 && (second == 18 || second == 19));
        }

        if (bytes.length == 16) {
            var first = bytes[0] & 0xff;
            var second = bytes[1] & 0xff;
            return (first & 0xfe) == 0xfc
                || (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
        }

        return false;
    }

    private void validateRemoteImageHeaders(HttpResponse<?> response) {
        var contentType = response.headers().firstValue("Content-Type")
            .map(value -> value.split(";", 2)[0].trim().toLowerCase())
            .orElse("");
        if (!contentType.startsWith("image/")) {
            throw new ProviderException("Remote image URL must return an image/* content type.", 400);
        }

        var contentLength = response.headers().firstValueAsLong("Content-Length");
        if (contentLength.isPresent() && contentLength.getAsLong() > maxRemoteImageBytes) {
            throw new ProviderException("Remote image exceeds maximum size of " + maxRemoteImageBytes + " bytes.", 400);
        }
    }

    private byte[] readRemoteImageBody(InputStream body) throws IOException {
        var output = new ByteArrayOutputStream(Math.min(maxRemoteImageBytes, REMOTE_IMAGE_READ_BUFFER_BYTES));
        var buffer = new byte[REMOTE_IMAGE_READ_BUFFER_BYTES];
        int totalBytes = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            totalBytes += read;
            if (totalBytes > maxRemoteImageBytes) {
                throw new ProviderException("Remote image exceeds maximum size of " + maxRemoteImageBytes + " bytes.", 400);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    
}
