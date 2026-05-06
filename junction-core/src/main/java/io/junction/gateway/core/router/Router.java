package io.junction.gateway.core.router;

import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.provider.LlmProvider;
import java.time.Instant;
import java.util.List;

public interface Router {
    default LlmProvider route(ChatCompletionRequest request) {
        return route(request, null);
    }

    LlmProvider route(ChatCompletionRequest request, String preferredProvider);
    LlmProvider route(EmbeddingRequest request);
    
    /**
     * Returns all available providers.
     * 
     * @return list of all configured providers
     */
    List<LlmProvider> getProviders();

    /**
     * Returns a cached provider health snapshot without performing live provider I/O.
     *
     * <p>Implementations that do not maintain cached health may return an unknown
     * snapshot for configured providers. Actuator and administrative endpoints should
     * use this method instead of calling {@link LlmProvider#isHealthy()} directly.</p>
     */
    default List<ProviderHealthSnapshot> getProviderHealthSnapshots() {
        return getProviders().stream()
            .map(provider -> new ProviderHealthSnapshot(
                provider.providerId(),
                null,
                null,
                provider.supportsEmbeddings(),
                provider.supportsImageInputs()
            ))
            .toList();
    }

    record ProviderHealthSnapshot(
        String providerId,
        Boolean healthy,
        Instant checkedAt,
        boolean supportsEmbeddings,
        boolean supportsImageInputs
    ) {
    }
}
