package io.junction.gateway.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(
    String id,
    String object,
    long created,
    String model,
    List<Choice> choices,
    Usage usage
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
        int index,
        Message message,
        @JsonProperty("finish_reason") String finishReason
    ) {}
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls
    ) {
        public Message(String role, String content) {
            this(role, content, null);
        }
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
    }
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Function(
        String name,
        String arguments
    ) {}
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
    ) {}
    
    public static ChatCompletionResponse complete(String model, String content) {
        return complete(model, content, null);
    }
    
    public static ChatCompletionResponse complete(String model, String content, List<ToolCall> toolCalls) {
        return new ChatCompletionResponse(
            "chatcmpl-" + UUID.randomUUID(),
            "chat.completion",
            System.currentTimeMillis() / 1000,
            model,
            List.of(new Choice(
                0,
                new Message("assistant", content, toolCalls),
                toolCalls != null && !toolCalls.isEmpty() ? "tool_calls" : "stop"
            )),
            new Usage(0, 0, 0)
        );
    }
}
