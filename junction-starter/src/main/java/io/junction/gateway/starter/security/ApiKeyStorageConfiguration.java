package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.InMemoryApiKeyRepository;
import io.junction.gateway.starter.JunctionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

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
    @ConditionalOnMissingBean(ApiKeyRepository.class)
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
            "org.h2.Driver"
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
            "org.postgresql.Driver"
        );
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyRepository.class)
    @ConditionalOnProperty(prefix = "junction.security.api-key", name = "storage", havingValue = "postgresql")
    public ApiKeyRepository postgresqlApiKeyRepository(@Qualifier("junctionApiKeyPostgresqlDataSource") DataSource dataSource,
                                                       ApiKeyStorageConfigurationValidator validator) {
        return new JdbcApiKeyRepository(dataSource);
    }

    private DataSource createDataSource(String url, String username, String password, String driverClassName) {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
