package io.junction.gateway.core.model;

import java.util.List;

/**
 * Response wrapper for the /v1/models endpoint.
 * 
 * <p>OpenAI-compatible list response containing model information.
 * 
 * @author Juan Hidalgo
 * @since 0.0.2
 */
public record ModelList(
    String object,
    List<ModelInfo> data
) {
    /**
     * Creates a model list from a list of ModelInfo objects.
     * 
     * @param models the list of models
     * @return a new ModelList instance
     */
    public static ModelList of(List<ModelInfo> models) {
        return new ModelList("list", models);
    }
}