package io.junction.gateway.core.router;

import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.exception.RouterException;
import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.Locale;

public class RoundRobinRouter implements Router {
    private final List<LlmProvider> providers;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ExecutorService executor;
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
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
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

            var parentTrace = tracing.capture();
            var healthFutures = eligibleProviders.stream()
                .map(provider -> CompletableFuture.supplyAsync(
                    () -> {
                        try (var healthSpan = tracing.startSpan("junction.provider.health", parentTrace)) {
                            healthSpan.tag("junction.provider", provider.providerId());
                            healthSpan.tag("junction.operation", operation);

                            var startNanos = System.nanoTime();
                            try {
                                var healthy = provider.isHealthy();
                                healthSpan.tag("junction.healthy", Boolean.toString(healthy));
                                telemetry.recordProviderHealthCheck(
                                    provider.providerId(),
                                    healthy,
                                    System.nanoTime() - startNanos
                                );
                                return new HealthResult(provider, healthy);
                            } catch (RuntimeException ex) {
                                healthSpan.tag("junction.healthy", "false");
                                healthSpan.error(ex);
                                throw ex;
                            }
                        }
                    },
                    executor
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(healthFutures.toArray(new CompletableFuture[0])).join();

            var healthy = healthFutures.stream()
                .map(CompletableFuture::join)
                .filter(HealthResult::healthy)
                .map(HealthResult::provider)
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

            var preferredDefaultProvider = healthy.stream()
                .filter(provider -> "ollama".equals(provider.providerId()))
                .findFirst();
            if (preferredDefaultProvider.isPresent()) {
                var selected = preferredDefaultProvider.get();
                routeSpan.tag("junction.provider", selected.providerId());
                routeSpan.tag("junction.outcome", "default_provider");
                telemetry.recordRouteSelection(operation, selected.providerId(), false);
                return selected;
            }

            var idx = counter.getAndIncrement() % healthy.size();
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
    
    private record HealthResult(LlmProvider provider, boolean healthy) {}
    
    @Override
    public List<LlmProvider> getProviders() {
        return providers;
    }
}
