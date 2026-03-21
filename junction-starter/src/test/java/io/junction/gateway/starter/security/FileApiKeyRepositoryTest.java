package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileApiKeyRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsCrudCountsFiltersAndReloadsFromDisk() {
        Path storagePath = tempDir.resolve("api-keys.yml");
        FileApiKeyRepository repository = new FileApiKeyRepository(storagePath.toString());

        ApiKey activeKey = createApiKey("Active Key", ApiKey.Tier.FREE, ApiKey.Status.ACTIVE);
        ApiKey suspendedKey = createApiKey("Suspended Key", ApiKey.Tier.PRO, ApiKey.Status.SUSPENDED);

        repository.save(activeKey);
        repository.save(suspendedKey);

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findById(activeKey.id())).contains(activeKey);
        assertThat(repository.findByKeyHash(activeKey.keyHash())).contains(activeKey);
        assertThat(repository.findByKeyPrefix(activeKey.keyPrefix())).contains(activeKey);
        assertThat(repository.findByStatus(ApiKey.Status.ACTIVE)).containsExactly(activeKey);
        assertThat(repository.findByTier(ApiKey.Tier.PRO)).containsExactly(suspendedKey);
        assertThat(repository.existsByKeyHash(suspendedKey.keyHash())).isTrue();

        assertThat(repository.deleteById(suspendedKey.id())).isTrue();
        assertThat(repository.count()).isEqualTo(1);

        FileApiKeyRepository reloaded = new FileApiKeyRepository(storagePath.toString());
        assertThat(reloaded.count()).isEqualTo(1);

        ApiKey persisted = reloaded.findById(activeKey.id()).orElseThrow();
        assertThat(persisted.name()).isEqualTo(activeKey.name());
        assertThat(persisted.description()).isEqualTo(activeKey.description());
        assertThat(persisted.allowedModels()).containsExactly("llama3.1");
        assertThat(persisted.allowedIps()).containsExactly("127.0.0.1");
        assertThat(persisted.metadata().organization()).isEqualTo("Junction");
        assertThat(persisted.metadata().customFields()).containsEntry("env", "test");
    }

    @Test
    void incrementUsageWritesUpdatedStateToDisk() {
        Path storagePath = tempDir.resolve("api-keys.yml");
        FileApiKeyRepository repository = new FileApiKeyRepository(storagePath.toString());
        ApiKey apiKey = createApiKey("Usage Key", ApiKey.Tier.ENTERPRISE, ApiKey.Status.ACTIVE);

        repository.save(apiKey);

        ApiKey updated = repository.incrementUsage(apiKey.id());
        assertThat(updated.requestCount()).isEqualTo(1);
        assertThat(updated.lastUsedAt()).isNotNull();

        FileApiKeyRepository reloaded = new FileApiKeyRepository(storagePath.toString());
        ApiKey persisted = reloaded.findById(apiKey.id()).orElseThrow();
        assertThat(persisted.requestCount()).isEqualTo(1);
        assertThat(persisted.lastUsedAt()).isNotNull();
    }

    private ApiKey createApiKey(String name, ApiKey.Tier tier, ApiKey.Status status) {
        return new ApiKey.Builder()
            .id(UUID.randomUUID().toString())
            .keyHash("hash_" + UUID.randomUUID())
            .keyPrefix("junc_" + UUID.randomUUID().toString().substring(0, 8))
            .name(name)
            .description("Persistent test key")
            .tier(tier)
            .status(status)
            .allowedModels(java.util.Set.of("llama3.1"))
            .allowedIps(java.util.Set.of("127.0.0.1"))
            .createdAt(Instant.parse("2026-03-19T00:00:00Z"))
            .createdBy("test-suite")
            .metadata(new ApiKey.Metadata("Junction", "ops@example.com", null, Map.of("env", "test")))
            .build();
    }
}
