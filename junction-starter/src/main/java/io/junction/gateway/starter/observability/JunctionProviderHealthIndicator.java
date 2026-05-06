package io.junction.gateway.starter.observability;

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
        var providers = router.getProviderHealthSnapshots();
        if (providers.isEmpty()) {
            return Health.unknown()
                .withDetail("configuredProviders", 0)
                .withDetail("providers", Map.of())
                .build();
        }

        var providerDetails = new LinkedHashMap<String, Object>();
        var anyHealthy = false;
        var anyUnknown = false;
        for (var provider : providers) {
            var healthy = provider.healthy();
            anyHealthy |= Boolean.TRUE.equals(healthy);
            anyUnknown |= healthy == null;

            var details = new LinkedHashMap<String, Object>();
            details.put("healthy", healthy != null ? healthy : "unknown");
            details.put("checkedAt", provider.checkedAt());
            details.put("supportsEmbeddings", provider.supportsEmbeddings());
            details.put("supportsImageInputs", provider.supportsImageInputs());
            providerDetails.put(provider.providerId(), details);
        }

        var builder = anyHealthy ? Health.up() : (anyUnknown ? Health.unknown() : Health.down());
        return builder
            .withDetail("configuredProviders", providers.size())
            .withDetail("providers", providerDetails)
            .build();
    }
}
