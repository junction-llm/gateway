package io.junction.gateway.starter.observability;

import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.router.Router;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

public class JunctionProviderHealthIndicator implements HealthIndicator {
    private final Router router;

    public JunctionProviderHealthIndicator(Router router) {
        this.router = router;
    }

    @Override
    public Health health() {
        var providers = router.getProviders();
        if (providers.isEmpty()) {
            return Health.unknown()
                .withDetail("configuredProviders", 0)
                .withDetail("providers", Map.of())
                .build();
        }

        var providerDetails = new LinkedHashMap<String, Object>();
        var anyHealthy = false;
        for (LlmProvider provider : providers) {
            var healthy = provider.isHealthy();
            anyHealthy |= healthy;

            providerDetails.put(provider.providerId(), Map.of(
                "healthy", healthy,
                "supportsEmbeddings", provider.supportsEmbeddings(),
                "supportsImageInputs", provider.supportsImageInputs()
            ));
        }

        var builder = anyHealthy ? Health.up() : Health.down();
        return builder
            .withDetail("configuredProviders", providers.size())
            .withDetail("providers", providerDetails)
            .build();
    }
}
