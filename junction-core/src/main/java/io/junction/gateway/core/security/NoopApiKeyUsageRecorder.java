package io.junction.gateway.core.security;

/**
 * Usage recorder that intentionally does not persist request counts.
 */
public class NoopApiKeyUsageRecorder implements ApiKeyUsageRecorder {

    @Override
    public void recordUsage(String apiKeyId) {
        // Intentionally empty.
    }
}
