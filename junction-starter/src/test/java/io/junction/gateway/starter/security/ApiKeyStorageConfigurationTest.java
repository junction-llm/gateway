package io.junction.gateway.starter.security;

import com.zaxxer.hikari.HikariDataSource;
import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.ApiKeyUsageRecorder;
import io.junction.gateway.core.security.NoopApiKeyUsageRecorder;
import io.junction.gateway.core.security.SyncApiKeyUsageRecorder;
import io.junction.gateway.starter.JunctionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyStorageConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(StorageTestConfiguration.class);

    @Test
    void bindsIpRateLimiterMaxIpStatesProperty() {
        contextRunner
            .withPropertyValues(
                "junction.security.ip-rate-limit.requests-per-minute=7",
                "junction.security.ip-rate-limit.requests-per-hour=70",
                "junction.security.ip-rate-limit.enabled=true",
                "junction.security.ip-rate-limit.max-ip-states=42"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                JunctionProperties.IpRateLimit config = context.getBean(JunctionProperties.class)
                    .getSecurity()
                    .getIpRateLimit();
                assertThat(config.getRequestsPerMinute()).isEqualTo(7);
                assertThat(config.getRequestsPerHour()).isEqualTo(70);
                assertThat(config.isEnabled()).isTrue();
                assertThat(config.getMaxIpStates()).isEqualTo(42);
            });
    }

    @Test
    void usesInMemoryRepositoryByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ApiKeyRepository.class);
            assertThat(context.getBean(ApiKeyRepository.class)).isInstanceOf(io.junction.gateway.core.security.InMemoryApiKeyRepository.class);
        });
    }

    @Test
    void usesFileRepositoryWhenConfigured() throws Exception {
        Path storagePath = tempDir.resolve("storage.yml");

        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=file",
                "junction.security.api-key.file-path=" + storagePath
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ApiKeyRepository.class)).isInstanceOf(FileApiKeyRepository.class);
            });
    }

    @Test
    void usesJdbcRepositoryForH2Storage() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=h2",
                "junction.security.api-key.h2-url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "junction.security.api-key.h2-username=sa",
                "junction.security.api-key.h2-password="
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ApiKeyRepository.class)).isInstanceOf(JdbcApiKeyRepository.class);
                assertThat(context.getBean("junctionApiKeyH2DataSource", DataSource.class))
                    .isInstanceOf(HikariDataSource.class);
            });
    }

    @Test
    void bindsJdbcPoolSettingsForH2Storage() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=h2",
                "junction.security.api-key.h2-url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "junction.security.api-key.h2-username=sa",
                "junction.security.api-key.h2-password=",
                "junction.security.api-key.jdbc-pool-maximum-pool-size=3",
                "junction.security.api-key.jdbc-pool-minimum-idle=1",
                "junction.security.api-key.jdbc-pool-connection-timeout-millis=750",
                "junction.security.api-key.jdbc-pool-idle-timeout-millis=12000",
                "junction.security.api-key.jdbc-pool-max-lifetime-millis=45000",
                "junction.security.api-key.jdbc-pool-initialization-fail-timeout-millis=0"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                HikariDataSource dataSource = context.getBean("junctionApiKeyH2DataSource", HikariDataSource.class);
                assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
                assertThat(dataSource.getMinimumIdle()).isEqualTo(1);
                assertThat(dataSource.getConnectionTimeout()).isEqualTo(750);
                assertThat(dataSource.getIdleTimeout()).isEqualTo(12000);
                assertThat(dataSource.getMaxLifetime()).isEqualTo(45000);
                assertThat(dataSource.getInitializationFailTimeout()).isEqualTo(0);
            });
    }

    @Test
    void usesJdbcRepositoryForPostgresqlStorageWhenCustomDataSourceIsProvided() {
        new ApplicationContextRunner()
            .withUserConfiguration(StorageTestConfiguration.class)
            .withBean("junctionApiKeyPostgresqlDataSource", DataSource.class, () -> {
                DriverManagerDataSource dataSource = new DriverManagerDataSource();
                dataSource.setDriverClassName("org.h2.Driver");
                dataSource.setUrl("jdbc:h2:mem:postgres-override-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                dataSource.setUsername("sa");
                dataSource.setPassword("");
                return dataSource;
            })
            .withPropertyValues(
                "junction.security.api-key.storage=postgresql",
                "junction.security.api-key.postgresql-url=jdbc:postgresql://localhost:5432/junction",
                "junction.security.api-key.postgresql-username=junction",
                "junction.security.api-key.postgresql-password=secret"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ApiKeyRepository.class)).isInstanceOf(JdbcApiKeyRepository.class);
            });
    }

    @Test
    void createsHikariDataSourceForPostgresqlStorage() {
        new ApplicationContextRunner()
            .withUserConfiguration(StorageTestConfiguration.class)
            .withBean(ApiKeyRepository.class, ApiKeyStorageConfigurationTest::stubApiKeyRepository)
            .withPropertyValues(
                "junction.security.api-key.storage=postgresql",
                "junction.security.api-key.postgresql-url=jdbc:postgresql://localhost:5432/junction",
                "junction.security.api-key.postgresql-username=junction",
                "junction.security.api-key.postgresql-password=secret",
                "junction.security.api-key.jdbc-pool-maximum-pool-size=2",
                "junction.security.api-key.jdbc-pool-minimum-idle=0",
                "junction.security.api-key.jdbc-pool-initialization-fail-timeout-millis=-1"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                HikariDataSource dataSource = context.getBean("junctionApiKeyPostgresqlDataSource", HikariDataSource.class);
                assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/junction");
                assertThat(dataSource.getUsername()).isEqualTo("junction");
                assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);
                assertThat(dataSource.getInitializationFailTimeout()).isEqualTo(-1);
                assertThat(context).hasSingleBean(ApiKeyRepository.class);
            });
    }

    @Test
    void failsForInvalidJdbcPoolSize() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=h2",
                "junction.security.api-key.h2-url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "junction.security.api-key.jdbc-pool-maximum-pool-size=0"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("jdbc-pool-maximum-pool-size");
            });
    }

    @Test
    void failsWhenJdbcPoolMinimumIdleExceedsMaximumPoolSize() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=h2",
                "junction.security.api-key.h2-url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "junction.security.api-key.jdbc-pool-maximum-pool-size=2",
                "junction.security.api-key.jdbc-pool-minimum-idle=3"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("jdbc-pool-minimum-idle");
            });
    }

    @Test
    void failsForInvalidJdbcPoolInitializationFailTimeout() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=h2",
                "junction.security.api-key.h2-url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "junction.security.api-key.jdbc-pool-initialization-fail-timeout-millis=-2"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("junction.security.api-key.jdbc-pool-initialization-fail-timeout-millis");
            });
    }

    @Test
    void failsForUnknownStorageType() {
        contextRunner
            .withPropertyValues("junction.security.api-key.storage=redis")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("junction.security.api-key.storage");
            });
    }

    @Test
    void failsForBlankFilePath() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=file",
                "junction.security.api-key.file-path="
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("junction.security.api-key.file-path");
            });
    }

    @Test
    void failsForBlankH2Url() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=h2",
                "junction.security.api-key.h2-url="
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("junction.security.api-key.h2-url");
            });
    }

    @Test
    void failsForBlankPostgresqlUrl() {
        contextRunner
            .withPropertyValues(
                "junction.security.api-key.storage=postgresql",
                "junction.security.api-key.postgresql-url="
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("junction.security.api-key.postgresql-url");
            });
    }


    @Test
    void usesAsyncUsageRecorderByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyUsageRecorder.class)).isInstanceOf(AsyncApiKeyUsageRecorder.class);
        });
    }

    @Test
    void usesAsyncUsageRecorderWhenConfigured() {
        contextRunner
            .withPropertyValues("junction.security.api-key.usage-recorder=async")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ApiKeyUsageRecorder.class)).isInstanceOf(AsyncApiKeyUsageRecorder.class);
            });
    }

    @Test
    void usesNoopUsageRecorderWhenConfigured() {
        contextRunner
            .withPropertyValues("junction.security.api-key.usage-recorder=noop")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ApiKeyUsageRecorder.class)).isInstanceOf(NoopApiKeyUsageRecorder.class);
            });
    }

    @Test
    void failsForUnknownUsageRecorderMode() {
        contextRunner
            .withPropertyValues("junction.security.api-key.usage-recorder=fire-and-forget")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("junction.security.api-key.usage-recorder");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ApiKeyStorageConfiguration.class)
    @EnableConfigurationProperties(JunctionProperties.class)
    static class StorageTestConfiguration {
    }

    private static ApiKeyRepository stubApiKeyRepository() {
        return new ApiKeyRepository() {
            @Override
            public io.junction.gateway.core.security.ApiKey save(io.junction.gateway.core.security.ApiKey apiKey) {
                return apiKey;
            }

            @Override
            public Optional<io.junction.gateway.core.security.ApiKey> findById(String id) {
                return Optional.empty();
            }

            @Override
            public Optional<io.junction.gateway.core.security.ApiKey> findByKeyHash(String keyHash) {
                return Optional.empty();
            }

            @Override
            public Optional<io.junction.gateway.core.security.ApiKey> findByKeyPrefix(String keyPrefix) {
                return Optional.empty();
            }

            @Override
            public List<io.junction.gateway.core.security.ApiKey> findAll() {
                return List.of();
            }

            @Override
            public List<io.junction.gateway.core.security.ApiKey> findByStatus(io.junction.gateway.core.security.ApiKey.Status status) {
                return List.of();
            }

            @Override
            public List<io.junction.gateway.core.security.ApiKey> findByTier(io.junction.gateway.core.security.ApiKey.Tier tier) {
                return List.of();
            }

            @Override
            public boolean deleteById(String id) {
                return false;
            }

            @Override
            public boolean existsByKeyHash(String keyHash) {
                return false;
            }

            @Override
            public long count() {
                return 0;
            }

            @Override
            public long countByStatus(io.junction.gateway.core.security.ApiKey.Status status) {
                return 0;
            }

            @Override
            public io.junction.gateway.core.security.ApiKey incrementUsage(String id) {
                throw new UnsupportedOperationException("Not used by this test");
            }
        };
    }
}
