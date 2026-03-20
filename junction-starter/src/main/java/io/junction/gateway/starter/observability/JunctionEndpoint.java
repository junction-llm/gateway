package io.junction.gateway.starter.observability;

import io.junction.gateway.core.cache.ModelCacheService;
import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.starter.JunctionProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.List;

@Endpoint(id = "junction")
public class JunctionEndpoint {
    private final Router router;
    private final ModelCacheService modelCacheService;
    private final ApiKeyRepository apiKeyRepository;
    private final JunctionProperties properties;

    public JunctionEndpoint(Router router,
                            ModelCacheService modelCacheService,
                            ApiKeyRepository apiKeyRepository,
                            JunctionProperties properties) {
        this.router = router;
        this.modelCacheService = modelCacheService;
        this.apiKeyRepository = apiKeyRepository;
        this.properties = properties;
    }

    @ReadOperation
    public JunctionSnapshot snapshot() {
        var providers = router.getProviders().stream()
            .map(provider -> new ProviderSnapshot(
                provider.providerId(),
                provider.isHealthy(),
                provider.supportsEmbeddings(),
                provider.supportsImageInputs()
            ))
            .sorted(java.util.Comparator.comparing(ProviderSnapshot::providerId))
            .toList();

        return new JunctionSnapshot(
            providers,
            modelCacheService.snapshot(),
            new ApiKeySummary(
                apiKeyRepository.count(),
                apiKeyRepository.countByStatus(ApiKey.Status.ACTIVE),
                apiKeyRepository.countByStatus(ApiKey.Status.SUSPENDED),
                apiKeyRepository.countByStatus(ApiKey.Status.REVOKED),
                apiKeyRepository.countByStatus(ApiKey.Status.EXPIRED)
            ),
            new ConfigSummary(
                properties.getApiKeyConfig().isRequired(),
                properties.getApiKeyConfig().getStorage(),
                properties.getLogging().getChatResponse().isEnabled(),
                properties.getObservability().getAdmin().isCacheWriteEnabled()
            )
        );
    }

    public record JunctionSnapshot(
        List<ProviderSnapshot> providers,
        List<ModelCacheService.CacheSnapshot> modelCache,
        ApiKeySummary apiKeys,
        ConfigSummary config
    ) {
    }

    public record ProviderSnapshot(
        String providerId,
        boolean healthy,
        boolean supportsEmbeddings,
        boolean supportsImageInputs
    ) {
    }

    public record ApiKeySummary(
        long total,
        long active,
        long suspended,
        long revoked,
        long expired
    ) {
    }

    public record ConfigSummary(
        boolean apiKeyRequired,
        String apiKeyStorage,
        boolean chatResponseLoggingEnabled,
        boolean cacheWriteEnabled
    ) {
    }
}
