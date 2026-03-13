package io.junction.gateway.core.cache;

import io.junction.gateway.core.model.ModelInfo;
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
            return entry.models();
        }
        
        log.info("Cache miss for {}, fetching fresh models", providerName);
        var models = fetcher.get();
        
        var expiry = Instant.now().plus(CACHE_TTL);
        cache.put(providerId, new CacheEntry(models, expiry));
        
        log.info("Cached {} models for {} (expires in {} hours)", 
            models.size(), providerName, CACHE_TTL.toHours());
        
        return models;
    }
    
    /**
     * Evicts a specific provider's cache entry.
     * 
     * @param providerId the provider identifier to evict
     * @return void
     */
    public void evictCache(String providerId) {
        cache.remove(providerId);
        log.debug("Evicted cache for provider {}", providerId);
    }
    
    /**
     * Evicts all cached models.
     * 
     * @return void
     */
    public void evictAll() {
        cache.clear();
        log.info("Evicted all cached models");
    }
    
    /**
     * Gets the number of providers with cached models.
     * 
     * @return number of cached providers
     */
    public int getCacheSize() {
        return cache.size();
    }
}
