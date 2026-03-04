package io.junction.gateway.core.security;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link ApiKeyRepository}.
 * 
 * <p>Default storage implementation using ConcurrentHashMap for thread safety.
 * Suitable for single-node deployments and development/testing.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class InMemoryApiKeyRepository implements ApiKeyRepository {
    
    private final Map<String, ApiKey> keysById = new ConcurrentHashMap<>();
    private final Map<String, ApiKey> keysByHash = new ConcurrentHashMap<>();
    private final Map<String, ApiKey> keysByPrefix = new ConcurrentHashMap<>();
    
    @Override
    public ApiKey save(ApiKey apiKey) {
        Objects.requireNonNull(apiKey, "apiKey cannot be null");
        
        keysById.put(apiKey.id(), apiKey);
        keysByHash.put(apiKey.keyHash(), apiKey);
        keysByPrefix.put(apiKey.keyPrefix(), apiKey);
        
        return apiKey;
    }
    
    @Override
    public Optional<ApiKey> findById(String id) {
        return Optional.ofNullable(keysById.get(id));
    }
    
    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return Optional.ofNullable(keysByHash.get(keyHash));
    }
    
    @Override
    public Optional<ApiKey> findByKeyPrefix(String keyPrefix) {
        return Optional.ofNullable(keysByPrefix.get(keyPrefix));
    }
    
    @Override
    public List<ApiKey> findAll() {
        return List.copyOf(keysById.values());
    }
    
    @Override
    public List<ApiKey> findByStatus(ApiKey.Status status) {
        return keysById.values().stream()
            .filter(k -> k.status() == status)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<ApiKey> findByTier(ApiKey.Tier tier) {
        return keysById.values().stream()
            .filter(k -> k.tier() == tier)
            .collect(Collectors.toList());
    }
    
    @Override
    public boolean deleteById(String id) {
        ApiKey removed = keysById.remove(id);
        if (removed != null) {
            keysByHash.remove(removed.keyHash());
            keysByPrefix.remove(removed.keyPrefix());
            return true;
        }
        return false;
    }
    
    @Override
    public boolean existsByKeyHash(String keyHash) {
        return keysByHash.containsKey(keyHash);
    }
    
    @Override
    public long count() {
        return keysById.size();
    }
    
    @Override
    public long countByStatus(ApiKey.Status status) {
        return keysById.values().stream()
            .filter(k -> k.status() == status)
            .count();
    }
    
    @Override
    public ApiKey incrementUsage(String id) {
        ApiKey existing = keysById.get(id);
        if (existing == null) {
            throw new ApiKeyNotFoundException(id);
        }
        
        ApiKey updated = existing.withUsageUpdate();
        
        keysById.put(id, updated);
        keysByHash.put(updated.keyHash(), updated);
        keysByPrefix.put(updated.keyPrefix(), updated);
        
        return updated;
    }
    
    public void clear() {
        keysById.clear();
        keysByHash.clear();
        keysByPrefix.clear();
    }
}
