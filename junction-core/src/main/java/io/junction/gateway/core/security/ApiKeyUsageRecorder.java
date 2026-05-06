package io.junction.gateway.core.security;

/**
 * Records successful API-key usage outside the validation decision path.
 *
 * <p>Implementations should be best-effort: recording failures must not make an
 * otherwise valid request fail.
 */
@FunctionalInterface
public interface ApiKeyUsageRecorder {

    /**
     * Records a successful use of the API key with the given id.
     *
     * @param apiKeyId the API-key id
     */
    void recordUsage(String apiKeyId);
}
