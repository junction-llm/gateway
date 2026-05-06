package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.ApiKeyUsageRecorder;
import io.junction.gateway.core.security.NoopApiKeyUsageRecorder;
import io.junction.gateway.core.security.SyncApiKeyUsageRecorder;
import io.junction.gateway.core.security.InMemoryApiKeyRepository;
import io.junction.gateway.starter.JunctionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Configures API-key storage backends for the starter.
 * 
 * @author Juan Hidalgo
 * @since 0.0.4
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JunctionProperties.class)
public class ApiKeyStorageConfiguration {

    @Bean
    public ApiKeyStorageConfigurationValidator apiKeyStorageConfigurationValidator(JunctionProperties properties) {
        var validator = new ApiKeyStorageConfigurationValidator(properties);
        validator.validate();
        return validator;
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRepository.class)
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "memory", matchIfMissing = true)
    public ApiKeyRepository memoryApiKeyRepository(ApiKeyStorageConfigurationValidator validator) {
        return new InMemoryApiKeyRepository();
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRepository.class)
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "file")
    public ApiKeyRepository fileApiKeyRepository(JunctionProperties properties,
                                                 ApiKeyStorageConfigurationValidator validator) {
        return new FileApiKeyRepository(properties.getApiKeyConfig().getFilePath());
    }

    @Bean(name = "junctionApiKeyH2DataSource")
    @ConditionalOnMissingBean(name = "junctionApiKeyH2DataSource")
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "h2")
    public DataSource junctionApiKeyH2DataSource(JunctionProperties properties,
                                                 ApiKeyStorageConfigurationValidator validator) {
        return createDataSource(
            properties.getApiKeyConfig().getH2Url(),
            properties.getApiKeyConfig().getH2Username(),
            properties.getApiKeyConfig().getH2Password(),
            "org.h2.Driver",
            "junction-api-key-h2",
            properties
        );
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRepository.class)
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "h2")
    public ApiKeyRepository h2ApiKeyRepository(@Qualifier("junctionApiKeyH2DataSource") DataSource dataSource,
                                               ApiKeyStorageConfigurationValidator validator) {
        return new JdbcApiKeyRepository(dataSource);
    }

    @Bean(name = "junctionApiKeyPostgresqlDataSource")
    @ConditionalOnMissingBean(name = "junctionApiKeyPostgresqlDataSource")
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "postgresql")
    public DataSource junctionApiKeyPostgresqlDataSource(JunctionProperties properties,
                                                         ApiKeyStorageConfigurationValidator validator) {
        return createDataSource(
            properties.getApiKeyConfig().getPostgresqlUrl(),
            properties.getApiKeyConfig().getPostgresqlUsername(),
            properties.getApiKeyConfig().getPostgresqlPassword(),
            "org.postgresql.Driver",
            "junction-api-key-postgresql",
            properties
        );
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRepository.class)
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "postgresql")
    public ApiKeyRepository postgresqlApiKeyRepository(@Qualifier("junctionApiKeyPostgresqlDataSource") DataSource dataSource,
                                                       ApiKeyStorageConfigurationValidator validator) {
        return new JdbcApiKeyRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyUsageRecorder.class)
    public ApiKeyUsageRecorder apiKeyUsageRecorder(ApiKeyRepository repository, JunctionProperties props) {
        var config = props.getApiKeyConfig();
        String mode = config.getUsageRecorder() == null ? "async" : config.getUsageRecorder().trim().toLowerCase();
        return switch (mode) {
            case "sync" -> new SyncApiKeyUsageRecorder(repository);
            case "async" -> new AsyncApiKeyUsageRecorder(
                repository,
                config.getUsageRecorderMaxPendingKeys(),
                Duration.ofMillis(config.getUsageRecorderFlushIntervalMillis()),
                Duration.ofMillis(config.getUsageRecorderShutdownTimeoutMillis())
            );
            case "noop" -> new NoopApiKeyUsageRecorder();
            default -> throw new IllegalArgumentException(
                "junction.security.api-key.usage-recorder must be one of sync, async, or noop."
            );
        };
    }

    private DataSource createDataSource(String url, String username, String password, String driverClassName,
                                        String poolName, JunctionProperties properties) {
        var apiKeyConfig = properties.getApiKeyConfig();
        var config = new HikariConfig();
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName(poolName);
        config.setMaximumPoolSize(apiKeyConfig.getJdbcPoolMaximumPoolSize());
        config.setMinimumIdle(apiKeyConfig.getJdbcPoolMinimumIdle());
        config.setConnectionTimeout(apiKeyConfig.getJdbcPoolConnectionTimeoutMillis());
        config.setIdleTimeout(apiKeyConfig.getJdbcPoolIdleTimeoutMillis());
        config.setMaxLifetime(apiKeyConfig.getJdbcPoolMaxLifetimeMillis());
        config.setInitializationFailTimeout(apiKeyConfig.getJdbcPoolInitializationFailTimeoutMillis());
        return new HikariDataSource(config);
    }
}
