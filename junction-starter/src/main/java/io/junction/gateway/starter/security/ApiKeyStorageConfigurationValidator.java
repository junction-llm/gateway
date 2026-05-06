package io.junction.gateway.starter.security;

import io.junction.gateway.starter.JunctionProperties;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Validates API-key storage configuration before repository beans are created.
 * 
 * @author Juan Hidalgo
 * @since 0.0.4
 */
public final class ApiKeyStorageConfigurationValidator {
    private static final Set<String> SUPPORTED_STORAGE_TYPES = Set.of(
        "memory",
        "file",
        "h2",
        "postgresql"
    );

    private final JunctionProperties properties;

    public ApiKeyStorageConfigurationValidator(JunctionProperties properties) {
        this.properties = properties;
    }

    public void validate() {
        String storage = normalizedStorage();
        if (!SUPPORTED_STORAGE_TYPES.contains(storage)) {
            throw new IllegalStateException(
                "junction.security.api-key.storage must be one of memory, file, h2, or postgresql."
            );
        }

        if ("file".equals(storage) && !StringUtils.hasText(properties.getApiKeyConfig().getFilePath())) {
            throw new IllegalStateException(
                "junction.security.api-key.file-path must be set when junction.security.api-key.storage=file."
            );
        }

        if ("h2".equals(storage) && !StringUtils.hasText(properties.getApiKeyConfig().getH2Url())) {
            throw new IllegalStateException(
                "junction.security.api-key.h2-url must be set when junction.security.api-key.storage=h2."
            );
        }

        if ("postgresql".equals(storage) && !StringUtils.hasText(properties.getApiKeyConfig().getPostgresqlUrl())) {
            throw new IllegalStateException(
                "junction.security.api-key.postgresql-url must be set when junction.security.api-key.storage=postgresql."
            );
        }

        if ("h2".equals(storage) || "postgresql".equals(storage)) {
            validateJdbcPool();
        }
    }

    private void validateJdbcPool() {
        var apiKey = properties.getApiKeyConfig();
        if (apiKey.getJdbcPoolMaximumPoolSize() < 1) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-maximum-pool-size must be at least 1."
            );
        }
        if (apiKey.getJdbcPoolMinimumIdle() < 0) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-minimum-idle must be at least 0."
            );
        }
        if (apiKey.getJdbcPoolMinimumIdle() > apiKey.getJdbcPoolMaximumPoolSize()) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-minimum-idle must be less than or equal to jdbc-pool-maximum-pool-size."
            );
        }
        if (apiKey.getJdbcPoolConnectionTimeoutMillis() < 250) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-connection-timeout-millis must be at least 250."
            );
        }
        if (apiKey.getJdbcPoolIdleTimeoutMillis() < 10_000) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-idle-timeout-millis must be at least 10000."
            );
        }
        if (apiKey.getJdbcPoolMaxLifetimeMillis() < 30_000) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-max-lifetime-millis must be at least 30000."
            );
        }
        if (apiKey.getJdbcPoolInitializationFailTimeoutMillis() < -1) {
            throw new IllegalStateException(
                "junction.security.api-key.jdbc-pool-initialization-fail-timeout-millis must be at least -1."
            );
        }
    }

    private String normalizedStorage() {
        String configuredStorage = properties.getApiKeyConfig().getStorage();
        if (!StringUtils.hasText(configuredStorage)) {
            return "memory";
        }
        return configuredStorage.trim().toLowerCase(Locale.ROOT);
    }
}
