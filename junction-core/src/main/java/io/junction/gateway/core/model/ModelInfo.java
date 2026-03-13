package io.junction.gateway.core.model;

import java.util.Map;

/**
 * Represents an LLM model available through the gateway.
 * 
 * <p>OpenAI-compatible model info object returned by the /v1/models endpoint.
 * 
 * @see <a href="https://platform.openai.com/docs/api-reference/models/object">OpenAI Model Object</a>
 * 
 * @author Juan Hidalgo
 * @since 0.0.2
 */
public record ModelInfo(
    String id,
    String object,
    long created,
    String ownedBy
) {
    /**
     * Creates a model info instance with default values.
     * 
     * @param id the model identifier
     * @return a new ModelInfo instance
     */
    public static ModelInfo of(String id) {
        return new ModelInfo(
            id,
            "model",
            System.currentTimeMillis() / 1000,
            "junction"
        );
    }

    /**
     * Creates a model info instance with custom properties.
     * 
     * @param id the model identifier
     * @param properties additional properties to include in the response
     * @return a new ModelInfo instance
     */
    public static ModelInfo of(String id, Map<String, Object> properties) {
        var model = new ModelInfo(
            id,
            "model",
            System.currentTimeMillis() / 1000,
            properties.getOrDefault("owned_by", "junction").toString()
        );
        return model;
    }
}
