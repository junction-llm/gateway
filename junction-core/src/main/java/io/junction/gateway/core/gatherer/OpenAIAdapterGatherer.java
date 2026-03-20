package io.junction.gateway.core.gatherer;

import io.junction.gateway.core.model.*;
import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.exception.ProviderException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Gatherer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenAIAdapterGatherer implements Gatherer<ProviderResponse, OpenAIAdapterGatherer.State, ChatCompletionChunk> {
    
    public static class State {
        StringBuilder content = new StringBuilder();
        StringBuilder toolCallBuffer = new StringBuilder();
        String model;
        boolean inToolCall = false;
        int toolCallIndex = 0;
        ProviderResponse.Usage usage = null;
    }
    
    private final String targetModel;
    
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
        "<functions\\.(\\w+):(\\d+)>(.*?)</functions\\.\\w+>",
        Pattern.DOTALL
    );
    
    public OpenAIAdapterGatherer(String targetModel) {
        this.targetModel = targetModel;
    }
    
    @Override
    public Supplier<State> initializer() {
        return State::new;
    }
    
    @Override
    public Integrator<State, ProviderResponse, ChatCompletionChunk> integrator() {
        return (state, element, downstream) -> {
            switch (element) {
                case ProviderResponse.GeminiResponse(String text, _, _, boolean finished) -> {
                    ensureModel(state, null);
                    handleContent(state, text, finished, downstream);
                }
                case ProviderResponse.OllamaResponse(String content, String ignoredThinking, String model, boolean done, java.util.List<ProviderResponse.ToolCall> toolCalls, ProviderResponse.Usage responseUsage) -> {
                    ensureModel(state, model);
                    if (responseUsage != null) {
                        state.usage = responseUsage;
                    }
                    if (content != null && !content.isEmpty()) {
                        handleContent(state, content, false, downstream);
                    }
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        for (var tc : toolCalls) {
                            emitToolCall(state, tc.function().name(), tc.index() != null ? tc.index() : 0, 
                                        tc.function().arguments(), downstream);
                        }
                    }
                    if (done) {
                        if (state.usage != null) {
                            ChatCompletionChunk.Usage usage = new ChatCompletionChunk.Usage(
                                state.usage.promptTokens(),
                                state.usage.completionTokens(),
                                state.usage.totalTokens()
                            );
                            downstream.push(new ChatCompletionChunk(
                                "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", ""),
                                System.currentTimeMillis() / 1000,
                                state.model,
                                java.util.Collections.singletonList(
                                    new ChatCompletionChunk.ChunkChoice(
                                        0,
                                        new ChatCompletionChunk.ChunkChoice.Delta("", null),
                                        "stop"
                                    )
                                ),
                                usage
                            ));
                        } else {
                            downstream.push(ChatCompletionChunk.streaming(state.model, "", true));
                        }
                    }
                }
                case ProviderResponse.ErrorResponse(String error, int code) -> {
                    throw new ProviderException(error, code);
                }
            }
            return !downstream.isRejecting();
        };
    }
    
    private void ensureModel(State state, String providerModel) {
        if (state.model == null || state.model.isBlank()) {
            if (providerModel != null && !providerModel.isBlank()) {
                state.model = providerModel;
            } else {
                state.model = targetModel;
            }
        }
    }
    
    private void handleContent(State state, String text, boolean finished, 
                                Gatherer.Downstream<? super ChatCompletionChunk> downstream) {
        String contentToProcess = state.toolCallBuffer.toString() + text;
        state.toolCallBuffer.setLength(0);
        
        Matcher matcher = TOOL_CALL_PATTERN.matcher(contentToProcess);
        int lastEnd = 0;
        
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String textBefore = contentToProcess.substring(lastEnd, matcher.start());
                if (!textBefore.isEmpty()) {
                    downstream.push(ChatCompletionChunk.streaming(
                        state.model,
                        textBefore,
                        false
                    ));
                }
            }
            
            String functionName = matcher.group(1);
            String indexStr = matcher.group(2);
            String arguments = matcher.group(3);
            
            emitToolCall(state, functionName, Integer.parseInt(indexStr), arguments, downstream);
            
            lastEnd = matcher.end();
        }
        
        if (lastEnd < contentToProcess.length()) {
            String remaining = contentToProcess.substring(lastEnd);
            
            if (remaining.contains("<functions.")) {
                int toolStart = remaining.indexOf("<functions.");
                if (toolStart > 0) {
                    downstream.push(ChatCompletionChunk.streaming(
                        state.model,
                        remaining.substring(0, toolStart),
                        false
                    ));
                }
                state.toolCallBuffer.append(remaining.substring(toolStart));
            } else {
                if (finished) {
                    downstream.push(ChatCompletionChunk.streaming(
                        state.model,
                        remaining,
                        true
                    ));
                } else {
                    downstream.push(ChatCompletionChunk.streaming(
                        state.model,
                        remaining,
                        false
                    ));
                }
            }
        } else if (finished && state.toolCallBuffer.length() == 0) {
            downstream.push(ChatCompletionChunk.streaming(
                state.model,
                "",
                true
            ));
        }
    }
    
    private void emitToolCall(State state, String functionName, int index, String arguments,
                               Gatherer.Downstream<? super ChatCompletionChunk> downstream) {
        String callId = "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        
        java.util.List<ChatCompletionChunk.ToolCall> toolCalls = java.util.Collections.singletonList(
            new ChatCompletionChunk.ToolCall(
                index,
                callId,
                "function",
                new ChatCompletionChunk.ToolCall.Function(functionName, arguments)
            )
        );
        
        downstream.push(ChatCompletionChunk.streamingWithToolCalls(
            state.model,
            toolCalls,
            false
        ));
    }
}
