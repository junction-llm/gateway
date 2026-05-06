package io.junction.gateway.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Synchronous usage recorder that preserves the historical repository-backed
 * usage-count behavior.
 */
public class SyncApiKeyUsageRecorder implements ApiKeyUsageRecorder {

    private static final Logger logger = LoggerFactory.getLogger(SyncApiKeyUsageRecorder.class);

    private final ApiKeyRepository repository;

    public SyncApiKeyUsageRecorder(ApiKeyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }

    @Override
    public void recordUsage(String apiKeyId) {
        try {
            repository.incrementUsage(apiKeyId);
        } catch (RuntimeException e) {
            logger.warn("Failed to record API-key usage for id {}", apiKeyId, e);
        }
    }
}
