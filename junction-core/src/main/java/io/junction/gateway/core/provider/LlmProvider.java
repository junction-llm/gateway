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
    boolean supportsEmbeddings();
    
    Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter();
    
    Stream<ProviderResponse> execute(ChatCompletionRequest request);

    EmbeddingResponse embed(EmbeddingRequest request);
    
    default ChatCompletionChunk complete(ChatCompletionRequest request) {
        return execute(request)
            .gather(responseAdapter())
            .findFirst()
            .orElseThrow();
    }
    
    /**
     * Returns a list of available models from this provider.
     * 
     * <p>Default implementation returns empty list. Providers should override
     * this method to expose their available models.
     * 
     * @return list of available models, or empty list if not supported
     */
    default List<ModelInfo> getAvailableModels() {
        return List.of();
    }
}
