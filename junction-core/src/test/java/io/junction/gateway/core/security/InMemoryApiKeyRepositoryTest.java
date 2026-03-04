package io.junction.gateway.core.security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Unit tests for {@link InMemoryApiKeyRepository}.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
class InMemoryApiKeyRepositoryTest {
    private InMemoryApiKeyRepository repository;
    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
    }
    @Test
    void testSave_ApiKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        var saved = repository.save(apiKey);
        assertNotNull(saved.id());
        assertEquals("Test Key", saved.name());
        assertEquals(ApiKey.Tier.FREE, saved.tier());
    }
    @Test
    void testSave_ApiKeyNull_ThrowsException() {
        assertThrows(NullPointerException.class, () -> repository.save(null));
    }
    @Test
    void testFindById_SavedApiKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        repository.save(apiKey);
        var result = repository.findById(apiKey.id());
        assertTrue(result.isPresent());
        assertEquals(apiKey.id(), result.get().id());
    }
    @Test
    void testFindById_NotFound() {
        repository.save(createApiKey("Test Key", ApiKey.Tier.FREE));
        var result = repository.findById("non-existent-id");
        assertFalse(result.isPresent());
    }
    @Test
    void testFindByKeyHash_SavedApiKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        repository.save(apiKey);
        var result = repository.findByKeyHash(apiKey.keyHash());
        assertTrue(result.isPresent());
        assertEquals(apiKey.id(), result.get().id());
    }
    @Test
    void testFindByKeyHash_NotFound() {
        repository.save(createApiKey("Test Key", ApiKey.Tier.FREE));
        var result = repository.findByKeyHash("non-existent-hash");
        assertFalse(result.isPresent());
    }
    @Test
    void testFindByKeyPrefix_SavedApiKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        repository.save(apiKey);
        var result = repository.findByKeyPrefix(apiKey.keyPrefix());
        assertTrue(result.isPresent());
        assertEquals(apiKey.id(), result.get().id());
    }
    @Test
    void testFindByKeyPrefix_NotFound() {
        repository.save(createApiKey("Test Key", ApiKey.Tier.FREE));
        var result = repository.findByKeyPrefix("non-existent-prefix");
        assertFalse(result.isPresent());
    }
    @Test
    void testFindAll_EmptyRepository() {
        var result = repository.findAll();
        assertTrue(result.isEmpty());
    }
    @Test
    void testFindAll_SavedApiKeys() {
        repository.save(createApiKey("Key 1", ApiKey.Tier.FREE));
        repository.save(createApiKey("Key 2", ApiKey.Tier.PRO));
        repository.save(createApiKey("Key 3", ApiKey.Tier.ENTERPRISE));
        var result = repository.findAll();
        assertEquals(3, result.size());
    }
    @Test
    void testFindByStatus_ActiveKeys() {
        repository.save(createApiKey("Key 1", ApiKey.Tier.FREE));
        repository.save(createApiKey("Key 2", ApiKey.Tier.PRO, ApiKey.Status.ACTIVE));
        repository.save(createApiKey("Key 3", ApiKey.Tier.ENTERPRISE, ApiKey.Status.SUSPENDED));
        var result = repository.findByStatus(ApiKey.Status.ACTIVE);
        assertEquals(2, result.size());
    }
    @Test
    void testFindByStatus_SuspendedKeys() {
        repository.save(createApiKey("Key 1", ApiKey.Tier.FREE));
        repository.save(createApiKey("Key 2", ApiKey.Tier.PRO, ApiKey.Status.ACTIVE));
        repository.save(createApiKey("Key 3", ApiKey.Tier.ENTERPRISE, ApiKey.Status.SUSPENDED));
        var result = repository.findByStatus(ApiKey.Status.SUSPENDED);
        assertEquals(1, result.size());
        assertEquals("Key 3", result.get(0).name());
    }
    @Test
    void testFindByTier_FREE() {
        repository.save(createApiKey("Free Key", ApiKey.Tier.FREE));
        repository.save(createApiKey("Pro Key", ApiKey.Tier.PRO));
        repository.save(createApiKey("Enterprise Key", ApiKey.Tier.ENTERPRISE));
        var result = repository.findByTier(ApiKey.Tier.FREE);
        assertEquals(1, result.size());
        assertEquals("Free Key", result.get(0).name());
    }
    @Test
    void testFindByTier_PRO() {
        repository.save(createApiKey("Free Key", ApiKey.Tier.FREE));
        repository.save(createApiKey("Pro Key", ApiKey.Tier.PRO));
        repository.save(createApiKey("Enterprise Key", ApiKey.Tier.ENTERPRISE));
        var result = repository.findByTier(ApiKey.Tier.PRO);
        assertEquals(1, result.size());
        assertEquals("Pro Key", result.get(0).name());
    }
    @Test
    void testDeleteById_ExistingKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        repository.save(apiKey);
        var result = repository.deleteById(apiKey.id());
        assertTrue(result);
        assertFalse(repository.findById(apiKey.id()).isPresent());
    }
    @Test
    void testDeleteById_NonExistingKey() {
        repository.save(createApiKey("Test Key", ApiKey.Tier.FREE));
        var result = repository.deleteById("non-existent-id");
        assertFalse(result);
    }
    @Test
    void testExistsByKeyHash_ExistingKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        repository.save(apiKey);
        var result = repository.existsByKeyHash(apiKey.keyHash());
        assertTrue(result);
    }
    @Test
    void testExistsByKeyHash_NonExistingKey() {
        repository.save(createApiKey("Test Key", ApiKey.Tier.FREE));
        var result = repository.existsByKeyHash("non-existent-hash");
        assertFalse(result);
    }
    @Test
    void testCount_EmptyRepository() {
        var result = repository.count();
        assertEquals(0, result);
    }
    @Test
    void testCount_SavedApiKeys() {
        repository.save(createApiKey("Key 1", ApiKey.Tier.FREE));
        repository.save(createApiKey("Key 2", ApiKey.Tier.PRO));
        repository.save(createApiKey("Key 3", ApiKey.Tier.ENTERPRISE));
        var result = repository.count();
        assertEquals(3, result);
    }
    @Test
    void testCountByStatus_ActiveKeys() {
        repository.save(createApiKey("Key 1", ApiKey.Tier.FREE));
        repository.save(createApiKey("Key 2", ApiKey.Tier.PRO, ApiKey.Status.ACTIVE));
        repository.save(createApiKey("Key 3", ApiKey.Tier.ENTERPRISE, ApiKey.Status.SUSPENDED));
        var result = repository.countByStatus(ApiKey.Status.ACTIVE);
        assertEquals(2, result);
    }
    @Test
    void testIncrementUsage_ExistingKey() {
        var apiKey = createApiKey("Test Key", ApiKey.Tier.FREE);
        repository.save(apiKey);
        var initialCount = apiKey.requestCount();
        var updated = repository.incrementUsage(apiKey.id());
        assertEquals(initialCount + 1, updated.requestCount());
        assertNotNull(updated.lastUsedAt());
    }
    @Test
    void testIncrementUsage_NonExistingKey_ThrowsException() {
        assertThrows(InMemoryApiKeyRepository.ApiKeyNotFoundException.class, () -> repository.incrementUsage("non-existent-id"));
    }
    @Test
    void testClear_EmptyRepository() {
        repository.clear();
        assertEquals(0, repository.count());
    }
    @Test
    void testClear_SavedApiKeys() {
        repository.save(createApiKey("Key 1", ApiKey.Tier.FREE));
        repository.save(createApiKey("Key 2", ApiKey.Tier.PRO));
        assertEquals(2, repository.count());
        repository.clear();
        assertEquals(0, repository.count());
    }
    private ApiKey createApiKey(String name, ApiKey.Tier tier) {
        return createApiKey(name, tier, ApiKey.Status.ACTIVE);
    }
    private ApiKey createApiKey(String name, ApiKey.Tier tier, ApiKey.Status status) {
        String id = java.util.UUID.randomUUID().toString();
        String keyHash = "hash_" + java.util.UUID.randomUUID().toString();
        String keyPrefix = "junc_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return new ApiKey.Builder()
            .id(id)
            .keyHash(keyHash)
            .keyPrefix(keyPrefix)
            .name(name)
            .tier(tier)
            .status(status)
            .createdAt(Instant.now())
            .build();
    }
}