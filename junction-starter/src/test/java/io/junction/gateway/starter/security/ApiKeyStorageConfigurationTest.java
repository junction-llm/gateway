package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKeyRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyStorageConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(StorageTestConfiguration.class);

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

    @Configuration(proxyBeanMethods = false)
    @Import(ApiKeyStorageConfiguration.class)
    @EnableConfigurationProperties(JunctionProperties.class)
    static class StorageTestConfiguration {
    }
}
