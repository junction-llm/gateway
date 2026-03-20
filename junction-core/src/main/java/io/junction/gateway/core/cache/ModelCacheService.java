package io.junction.gateway.core.cache;

import io.junction.gateway.core.model.ModelInfo;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for caching model lists from LLM providers.
 * 
 * <p>Implements daily caching (24-hour TTL) to avoid hitting provider APIs
 * on every request. The cache is automatically invalidated after 24 hours.
 * 
 * @author Juan Hidalgo
 * @since 0.0.2
 */
public class ModelCacheService {
    private static final Logger log = LoggerFactory.getLogger(ModelCacheService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    
    private static record CacheEntry(List<ModelInfo> models, Instant expiry) {}
    
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final GatewayTelemetry telemetry;
    private final GatewayTracing tracing;

    public record CacheSnapshot(String providerId, int modelCount, Instant expiry) {}

    public ModelCacheService() {
        this(GatewayTelemetry.noop(), GatewayTracing.noop());
    }

    public ModelCacheService(GatewayTelemetry telemetry) {
        this(telemetry, GatewayTracing.noop());
    }

    public ModelCacheService(GatewayTelemetry telemetry, GatewayTracing tracing) {
        this.telemetry = telemetry != null ? telemetry : GatewayTelemetry.noop();
        this.tracing = tracing != null ? tracing : GatewayTracing.noop();
    }
    
    /**
     * Gets models from cache if valid, otherwise fetches and caches them.
     * 
     * @param providerId the provider identifier
     * @param fetcher the function to fetch models if cache is expired
     * @return list of available models
     */
    public List<ModelInfo> getModels(String providerId, String providerName, java.util.function.Supplier<List<ModelInfo>> fetcher) {
        var entry = cache.get(providerId);
        
        if (entry != null && Instant.now().isBefore(entry.expiry())) {
            log.debug("Cache hit for {} models ({} entries)", providerName, entry.models().size());
            telemetry.recordModelCacheHit(providerId);
            return entry.models();
        }
        
        log.info("Cache miss for {}, fetching fresh models", providerName);
        telemetry.recordModelCacheMiss(providerId);
        try (var traceScope = tracing.startSpan("junction.model_cache.refresh")) {
            traceScope.tag("junction.provider", providerId);
            traceScope.tag("junction.provider_name", providerName);
            var models = fetcher.get();
            traceScope.tag("junction.outcome", "success");
            var expiry = Instant.now().plus(CACHE_TTL);
            cache.put(providerId, new CacheEntry(models, expiry));

            log.info("Cached {} models for {} (expires in {} hours)",
                models.size(), providerName, CACHE_TTL.toHours());

            return models;
        } catch (RuntimeException ex) {
            try (var traceScope = tracing.startSpan("junction.model_cache.refresh.error")) {
                traceScope.tag("junction.provider", providerId);
                traceScope.tag("junction.provider_name", providerName);
                traceScope.tag("junction.outcome", "error");
                traceScope.error(ex);
            }
            throw ex;
        }
    }
    
    /**
     * Evicts a specific provider's cache entry.
     * 
     * @param providerId the provider identifier to evict
     * @return void
     */
    public void evictCache(String providerId) {
        try (var traceScope = tracing.startSpan("junction.model_cache.evict")) {
            traceScope.tag("junction.provider", providerId);
            cache.remove(providerId);
            log.debug("Evicted cache for provider {}", providerId);
            telemetry.recordModelCacheEviction(providerId);
            traceScope.tag("junction.outcome", "success");
        }
    }
    
    /**
     * Evicts all cached models.
     * 
     * @return void
     */
    public void evictAll() {
        try (var traceScope = tracing.startSpan("junction.model_cache.evict_all")) {
            traceScope.tag("junction.provider", "all");
            cache.clear();
            log.info("Evicted all cached models");
            telemetry.recordModelCacheEviction("all");
            traceScope.tag("junction.outcome", "success");
        }
    }
    
    /**
     * Gets the number of providers with cached models.
     * 
     * @return number of cached providers
     */
    public int getCacheSize() {
        return cache.size();
    }

    public List<CacheSnapshot> snapshot() {
        return cache.entrySet().stream()
            .map(entry -> new CacheSnapshot(
                entry.getKey(),
                entry.getValue().models().size(),
                entry.getValue().expiry()
            ))
            .sorted(java.util.Comparator.comparing(CacheSnapshot::providerId))
            .toList();
    }

    public Duration getCacheTtl() {
        return CACHE_TTL;
    }
}
