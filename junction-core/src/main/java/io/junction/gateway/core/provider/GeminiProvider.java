package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.model.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Gatherer;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

public final class GeminiProvider implements LlmProvider {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String apiKey;
    private final String model;
    
    public GeminiProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }
    
    @Override
    public String providerId() { return "gemini"; }
    
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
        var traceId = RequestContext.Context.current().traceId();
        var targetModel = request.model() != null && !request.model().isBlank() ? request.model() : model;
        
        var geminiBody = transformToGemini(request);
        
        var uri = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/models/" + 
            targetModel + ":streamGenerateContent?key=" + apiKey
        );
        
        var httpReq = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .header("X-Trace-ID", traceId.toString())
            .POST(HttpRequest.BodyPublishers.ofString(geminiBody))
            .build();
            
        try {
            var response = client.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return Stream.of(new ProviderResponse.ErrorResponse("Gemini error: HTTP " + response.statusCode(), response.statusCode()));
            }
            return parseGeminiStream(response.body());
        } catch (Exception e) {
            return Stream.of(new ProviderResponse.ErrorResponse(e.getMessage(), 500));
        }
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        throw new UnsupportedOperationException("Gemini embeddings are not supported in this release.");
    }
    
    private Stream<ProviderResponse> parseGeminiStream(java.io.InputStream is) {
        return new java.io.BufferedReader(new java.io.InputStreamReader(is))
            .lines()
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring(6))
            .map(json -> {
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
}
