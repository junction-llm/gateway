package io.junction.gateway.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(
    String id,
    long created,
    String model,
    @JsonProperty("choices") List<ChunkChoice> choices,
    @JsonProperty("usage") Usage usage
) {
    public ChatCompletionChunk(String id, long created, String model, List<ChunkChoice> choices) {
        this(id, created, model, choices, null);
    }
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
    ) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoice(
        int index,
        Delta delta,
        @JsonProperty("finish_reason") String finishReason
    ) {
        public record Delta(String content, @JsonProperty("tool_calls") List<ToolCall> toolCalls) {}
    }
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(
        Integer index,
        String id,
        @JsonProperty("type") String type,
        Function function
    ) {
        public ToolCall(String id, String type, Function function) {
            this(null, id, type, function);
        }
        
        public record Function(String name, String arguments) {}
    }
    
    public static ChatCompletionChunk streaming(String model, String content, boolean finished) {
        return streaming(model, content, finished, null);
    }
    
    public static ChatCompletionChunk streaming(String model, String content, boolean finished, List<ToolCall> toolCalls) {
        return new ChatCompletionChunk(
            "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
            System.currentTimeMillis() / 1000,
            model,
            Collections.singletonList(
                new ChunkChoice(
                    0,
                    new ChunkChoice.Delta(content, toolCalls),
                    finished ? "stop" : null
                )
            )
        );
    }
    
    public static ChatCompletionChunk streamingWithToolCalls(String model, List<ToolCall> toolCalls, boolean finished) {
        return new ChatCompletionChunk(
            "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
            System.currentTimeMillis() / 1000,
            model,
            Collections.singletonList(
                new ChunkChoice(
                    0,
                    new ChunkChoice.Delta(null, toolCalls),  // content is null, toolCalls is set
                    finished ? "stop" : null
                )
            )
        );
    }
}
