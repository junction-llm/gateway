package io.junction.gateway.core.model;

import java.util.List;

public sealed interface ProviderResponse {
    record GeminiResponse(String text, int promptTokens, int completionTokens, boolean finished) implements ProviderResponse {}
    record OllamaResponse(String content, String model, boolean done, List<ToolCall> toolCalls, Usage usage) implements ProviderResponse {
        public OllamaResponse(String content, String model, boolean done, List<ToolCall> toolCalls) {
            this(content, model, done, toolCalls, null);
        }
    }
    record ErrorResponse(String error, int code) implements ProviderResponse {}
    
    record ToolCall(String id, Integer index, FunctionCall function) {
        public ToolCall(String id, FunctionCall function) {
            this(id, null, function);
        }
    }
    record FunctionCall(String name, String arguments) {}
    
    record Usage(int promptTokens, int completionTokens, int totalTokens) {}
}
