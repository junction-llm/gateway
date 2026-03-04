package io.junction.gateway.core.router;

import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.exception.RouterException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class RoundRobinRouter implements Router {
    private final List<LlmProvider> providers;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ExecutorService executor;
    
    public RoundRobinRouter(List<LlmProvider> providers) {
        this.providers = providers;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    @Override
    public LlmProvider route(ChatCompletionRequest request) {
        var healthFutures = providers.stream()
            .map(provider -> CompletableFuture.supplyAsync(
                () -> new HealthResult(provider, provider.isHealthy()),
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
            throw new NoProviderAvailableException();
        }
        
        var idx = counter.getAndIncrement() % healthy.size();
        return healthy.get(idx);
    }
    
    private record HealthResult(LlmProvider provider, boolean healthy) {}
}