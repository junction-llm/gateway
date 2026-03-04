package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Gatherer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class OllamaProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private final HttpClient client;
    private final String baseUrl;
    private final String defaultModel;
    
    public OllamaProvider(String baseUrl, String defaultModel) {
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;

        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
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
    public Stream<ProviderResponse> execute(ChatCompletionRequest request) {
        var traceId = RequestContext.Context.current().traceId();
        
        var model = request.model() != null ? request.model() : defaultModel;
        var messagesJson = formatMessages(request.messages());
        var stream = request.stream();
        var temp = request.temperature() != null ? request.temperature() : 0.3;
        
        log.info("[{}] OllamaProvider executing request: model={}, stream={}, temp={}", 
                traceId, model, stream, temp);
        
        var json = """
            {
                "model": "%s",
                "messages": %s,
                "stream": %s,
                "options": {
                    "temperature": %s
                }
            }
            """.formatted(model, messagesJson, stream, temp);
        
        log.debug("[{}] Request body: {}", traceId, json);
        
        var httpReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("X-Trace-ID", traceId.toString())
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
            
        try {
            log.debug("[{}] Sending request to Ollama at: {}", traceId, baseUrl);
            var response = client.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            
            if (response.statusCode() != 200) {
                log.error("[{}] Ollama returned HTTP error: {}", traceId, response.statusCode());
                var errorBody = new String(response.body().readAllBytes());
                log.error("[{}] Error response body: {}", traceId, errorBody);
                return Stream.of(new ProviderResponse.ErrorResponse(
                    "Ollama error: HTTP " + response.statusCode() + " - " + errorBody, 
                    response.statusCode()));
            }
            
            log.debug("[{}] Received successful response from Ollama, parsing NDJSON stream", traceId);
            return parseNdJson(response.body(), traceId);
            
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("[{}] Timeout calling Ollama: {}", traceId, e.getMessage());
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama timeout: " + e.getMessage(), 504));
        } catch (java.io.IOException e) {
            log.error("[{}] IO error calling Ollama: {}", traceId, e.getMessage());
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama connection error: " + e.getMessage(), 502));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[{}] Request interrupted: {}", traceId, e.getMessage());
            return Stream.of(new ProviderResponse.ErrorResponse("Request interrupted", 500));
        } catch (Exception e) {
            log.error("[{}] Unexpected error calling Ollama: {}", traceId, e.getMessage(), e);
            return Stream.of(new ProviderResponse.ErrorResponse("Ollama error: " + e.getMessage(), 500));
        }
    }
    
    private Stream<ProviderResponse> parseNdJson(java.io.InputStream is, java.util.UUID traceId) {
        final java.util.concurrent.atomic.AtomicInteger chunkCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        
        var stream = new java.io.BufferedReader(new java.io.InputStreamReader(is))
            .lines()
            .map(line -> {
                try {
                    var mapper = new tools.jackson.databind.ObjectMapper();
                    var node = mapper.readTree(line);
                    
                    var content = node.path("message").path("content").asText("");
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
                    
                    if (currentChunk == 1 || currentChunk % 60 == 0 || done) {
                        if (!content.isEmpty() || (toolCalls != null && !toolCalls.isEmpty())) {
                            log.debug("[{}] Received chunk #{} - model: {}, content length: {}, toolCalls: {}, done: {}", 
                                traceId, currentChunk, model, content.length(), toolCalls != null ? toolCalls.size() : 0, done);
                        } else {
                            log.debug("[{}] Received empty chunk #{} - model: {}, done: {}", traceId, currentChunk, model, done);
                        }
                    }
                    
                    if (done) {
                        log.info("[{}] Ollama stream marked as complete", traceId);
                    }
                    
                    return (ProviderResponse) new ProviderResponse.OllamaResponse(content, model, done, toolCalls, usage);
                    
                } catch (Exception e) {
                    log.error("[{}] Parse error for line: {}", traceId, line, e);
                    return (ProviderResponse) new ProviderResponse.ErrorResponse("Parse error: " + line, 500);
                }
            });
        
        return stream.onClose(() -> log.info("[{}] NDJSON stream closed", traceId));
    }
    
    @Override
    public Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter() {
        return new io.junction.gateway.core.gatherer.OpenAIAdapterGatherer(defaultModel);
    }
    
    private String formatMessages(List<ChatCompletionRequest.Message> messages) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(
                "{\"role\": \"%s\", \"content\": \"%s\"}",
                m.role(),
                escapeJson(m.getTextContent())
            ));
        }
        sb.append("]");
        return sb.toString();
    }
    
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}