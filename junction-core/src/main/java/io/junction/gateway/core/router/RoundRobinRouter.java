package io.junction.gateway.core.router;

import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.exception.RouterException;
import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RoundRobinRouter implements Router {
    private static final Duration HEALTH_CACHE_TTL = Duration.ofSeconds(30);

    private final List<LlmProvider> providers;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ConcurrentMap<String, HealthResult> healthCache = new ConcurrentHashMap<>();
    private final GatewayTelemetry telemetry;
    private final GatewayTracing tracing;
    
    public RoundRobinRouter(List<LlmProvider> providers) {
        this(providers, GatewayTelemetry.noop(), GatewayTracing.noop());
    }

    public RoundRobinRouter(List<LlmProvider> providers, GatewayTelemetry telemetry) {
        this(providers, telemetry, GatewayTracing.noop());
    }

    public RoundRobinRouter(List<LlmProvider> providers, GatewayTelemetry telemetry, GatewayTracing tracing) {
        this.providers = providers;
        this.telemetry = telemetry != null ? telemetry : GatewayTelemetry.noop();
        this.tracing = tracing != null ? tracing : GatewayTracing.noop();
    }
    
    @Override
    public LlmProvider route(ChatCompletionRequest request) {
        return route(request, null);
    }

    @Override
    public LlmProvider route(ChatCompletionRequest request, String preferredProvider) {
        var requestHasImageInputs = request != null
            && request.messages() != null
            && request.messages().stream().anyMatch(ChatCompletionRequest.Message::hasImageParts);

        return routeAvailableProviders(
            requestHasImageInputs ? "chat_image" : "chat",
            requestHasImageInputs ? LlmProvider::supportsImageInputs : provider -> true,
            preferredProvider
        );
    }

    @Override
    public LlmProvider route(EmbeddingRequest request) {
        return routeAvailableProviders("embeddings", LlmProvider::supportsEmbeddings, null);
    }

    private LlmProvider routeAvailableProviders(String operation, Predicate<LlmProvider> capabilityFilter, String preferredProvider) {
        try (var routeSpan = tracing.startSpan("junction.router.select")) {
            routeSpan.tag("junction.operation", operation);
            if (preferredProvider != null && !preferredProvider.isBlank()) {
                routeSpan.tag("junction.preferred_provider", preferredProvider);
            }

            var eligibleProviders = providers.stream()
                .filter(capabilityFilter)
                .toList();

            if (eligibleProviders.isEmpty()) {
                routeSpan.tag("junction.outcome", "no_provider");
                throw new NoProviderAvailableException();
            }

            var healthy = eligibleProviders.stream()
                .filter(this::isCachedHealthy)
                .collect(Collectors.toList());

            if (healthy.isEmpty()) {
                routeSpan.tag("junction.outcome", "no_provider");
                throw new NoProviderAvailableException();
            }

            var normalizedPreferred = normalizeProvider(preferredProvider);
            if (normalizedPreferred != null) {
                validateProviderExists(normalizedPreferred, providers);
                var selected = healthy.stream()
                    .filter(p -> p.providerId().equals(normalizedPreferred))
                    .findFirst()
                    .orElseThrow(NoProviderAvailableException::new);
                routeSpan.tag("junction.provider", selected.providerId());
                routeSpan.tag("junction.outcome", "preferred");
                telemetry.recordRouteSelection(operation, selected.providerId(), true);
                return selected;
            }

            var idx = Math.floorMod(counter.getAndIncrement(), healthy.size());
            var selected = healthy.get(idx);
            routeSpan.tag("junction.provider", selected.providerId());
            routeSpan.tag("junction.outcome", "round_robin");
            telemetry.recordRouteSelection(operation, selected.providerId(), false);
            return selected;
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    private static String normalizeProvider(String provider) {
        if (provider == null) {
            return null;
        }

        var normalized = provider.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private void validateProviderExists(String normalizedPreferredProvider, List<LlmProvider> eligibleProviders) {
        var providerExists = providers.stream()
            .map(provider -> normalizeProvider(provider.providerId()))
            .anyMatch(normalizedPreferredProvider::equals);

        if (!providerExists) {
            throw new RouterException(
                "Requested provider '" + normalizedPreferredProvider + "' is not configured for this request."
            );
        }
        
        var supportedByCapability = eligibleProviders.stream()
            .map(provider -> normalizeProvider(provider.providerId()))
            .anyMatch(normalizedPreferredProvider::equals);

        if (!supportedByCapability) {
            throw new NoProviderAvailableException();
        }
    }
    private boolean isCachedHealthy(LlmProvider provider) {
        var providerId = normalizeProvider(provider.providerId());
        var cached = healthCache.get(providerId);
        if (cached == null) {
            return true;
        }
        if (cached.checkedAt().plus(HEALTH_CACHE_TTL).isBefore(Instant.now())) {
            return true;
        }
        return cached.healthy();
    }

    /**
     * Updates cached provider health from a background or administrative health check.
     *
     * <p>This method intentionally only records externally-computed health. Request
     * routing must not call provider health endpoints synchronously.</p>
     */
    public void updateHealth(String providerId, boolean healthy) {
        var normalizedProviderId = normalizeProvider(providerId);
        if (normalizedProviderId != null) {
            healthCache.put(normalizedProviderId, new HealthResult(healthy, Instant.now()));
        }
    }

    /**
     * Performs and records a provider health check for callers that run outside the
     * request-routing path, such as scheduled health refresh jobs.
     */
    public boolean refreshHealth(LlmProvider provider, String operation) {
        try (var healthSpan = tracing.startSpan("junction.provider.health")) {
            healthSpan.tag("junction.provider", provider.providerId());
            healthSpan.tag("junction.operation", operation);
            var startNanos = System.nanoTime();
            try {
                var healthy = provider.isHealthy();
                updateHealth(provider.providerId(), healthy);
                healthSpan.tag("junction.healthy", Boolean.toString(healthy));
                telemetry.recordProviderHealthCheck(provider.providerId(), healthy, System.nanoTime() - startNanos);
                return healthy;
            } catch (RuntimeException ex) {
                updateHealth(provider.providerId(), false);
                healthSpan.tag("junction.healthy", "false");
                healthSpan.error(ex);
                telemetry.recordProviderHealthCheck(provider.providerId(), false, System.nanoTime() - startNanos);
                return false;
            }
        }
    }

    public void refreshHealth() {
        providers.forEach(provider -> refreshHealth(provider, "refresh"));
    }

    private record HealthResult(boolean healthy, Instant checkedAt) {}

    @Override
    public List<LlmProvider> getProviders() {
        return providers;
    }
}
