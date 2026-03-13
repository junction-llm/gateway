package io.junction.gateway.core.provider;

import io.junction.gateway.core.model.*;
import java.util.List;
import java.util.stream.Gatherer;
import java.util.stream.Stream;

public sealed interface LlmProvider permits 
    GeminiProvider,
    OllamaProvider {
    String providerId();
    boolean isHealthy();
    
    Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter();
    
    Stream<ProviderResponse> execute(ChatCompletionRequest request);
    
    default ChatCompletionChunk complete(ChatCompletionRequest request) {
        return execute(request)
            .gather(responseAdapter())
            .findFirst()
            .orElseThrow();
    }
    
    default List<ModelInfo> getAvailableModels() {
        return List.of();
    }
}
