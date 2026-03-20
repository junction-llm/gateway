package io.junction.gateway.starter.observability;

import io.junction.gateway.core.cache.ModelCacheService;
import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestControllerEndpoint(id = "junctioncache")
public class JunctionCacheEndpoint {
    private final ModelCacheService modelCacheService;

    public JunctionCacheEndpoint(ModelCacheService modelCacheService) {
        this.modelCacheService = modelCacheService;
    }

    @DeleteMapping
    public CacheEvictionResult evictAll() {
        modelCacheService.evictAll();
        return new CacheEvictionResult("all", null, modelCacheService.getCacheSize());
    }

    @DeleteMapping("/{providerId}")
    public CacheEvictionResult evictProvider(@PathVariable("providerId") String providerId) {
        modelCacheService.evictCache(providerId);
        return new CacheEvictionResult("provider", providerId, modelCacheService.getCacheSize());
    }

    public record CacheEvictionResult(String scope, String providerId, int cacheSizeAfter) {
    }
}
