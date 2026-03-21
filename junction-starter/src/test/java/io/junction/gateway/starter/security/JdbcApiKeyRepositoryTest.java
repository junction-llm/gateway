package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcApiKeyRepositoryTest {

    private DataSource dataSource;
    private JdbcApiKeyRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:api-key-repo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        this.dataSource = dataSource;
        repository = new JdbcApiKeyRepository(dataSource);
    }

    @Test
    void loadsSchemaFromClasspathResource() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();

            try (var tables = metadata.getTables(null, null, "JUNCTION_API_KEYS", new String[]{"TABLE"})) {
                assertThat(tables.next()).isTrue();
            }
            assertThat(indexNames(metadata, "JUNCTION_API_KEYS"))
                .contains(
                    "IDX_JUNCTION_API_KEYS_KEY_HASH",
                    "IDX_JUNCTION_API_KEYS_KEY_PREFIX",
                    "IDX_JUNCTION_API_KEYS_STATUS",
                    "IDX_JUNCTION_API_KEYS_TIER"
                );
        }
    }

    @Test
    void createsSchemaAndSupportsCrudCountsAndFilters() {
        ApiKey activeKey = createApiKey("Active JDBC Key", ApiKey.Tier.FREE, ApiKey.Status.ACTIVE);
        ApiKey suspendedKey = createApiKey("Suspended JDBC Key", ApiKey.Tier.PRO, ApiKey.Status.SUSPENDED);

        repository.save(activeKey);
        repository.save(suspendedKey);

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findById(activeKey.id())).contains(activeKey);
        assertThat(repository.findByStatus(ApiKey.Status.ACTIVE)).containsExactly(activeKey);
        assertThat(repository.findByTier(ApiKey.Tier.PRO)).containsExactly(suspendedKey);
        assertThat(repository.deleteById(suspendedKey.id())).isTrue();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void looksUpByHashAndPrefixAndPersistsUsageUpdates() {
        ApiKey apiKey = createApiKey("Lookup JDBC Key", ApiKey.Tier.ENTERPRISE, ApiKey.Status.ACTIVE);
        repository.save(apiKey);

        assertThat(repository.findByKeyHash(apiKey.keyHash())).contains(apiKey);
        assertThat(repository.findByKeyPrefix(apiKey.keyPrefix())).contains(apiKey);

        ApiKey updated = repository.incrementUsage(apiKey.id());
        assertThat(updated.requestCount()).isEqualTo(1);
        assertThat(updated.lastUsedAt()).isNotNull();

        ApiKey reloaded = repository.findById(apiKey.id()).orElseThrow();
        assertThat(reloaded.requestCount()).isEqualTo(1);
        assertThat(reloaded.lastUsedAt()).isNotNull();
        assertThat(reloaded.metadata().organization()).isEqualTo("Junction");
        assertThat(reloaded.metadata().customFields()).containsEntry("env", "jdbc-test");
    }

    private ApiKey createApiKey(String name, ApiKey.Tier tier, ApiKey.Status status) {
        return new ApiKey.Builder()
            .id(UUID.randomUUID().toString())
            .keyHash("hash_" + UUID.randomUUID())
            .keyPrefix("junc_" + UUID.randomUUID().toString().substring(0, 8))
            .name(name)
            .description("JDBC persistent test key")
            .tier(tier)
            .status(status)
            .allowedModels(java.util.Set.of("llama3.1"))
            .allowedIps(java.util.Set.of("127.0.0.1"))
            .createdAt(Instant.parse("2026-03-19T00:00:00Z"))
            .createdBy("jdbc-test-suite")
            .metadata(new ApiKey.Metadata("Junction", "ops@example.com", null, Map.of("env", "jdbc-test")))
            .build();
    }

    private Set<String> indexNames(DatabaseMetaData metadata, String tableName) throws Exception {
        Set<String> names = new HashSet<>();
        try (var indexes = metadata.getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                if (indexName != null) {
                    names.add(indexName);
                }
            }
        }
        return names;
    }
}
