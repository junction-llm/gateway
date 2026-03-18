package io.junction.gateway.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record EmbeddingRequest(
    String model,
    List<String> input,
    @JsonProperty("encoding_format") String encodingFormat,
    Integer dimensions,
    String user
) {
    public EmbeddingRequest {
        input = input == null ? List.of() : List.copyOf(input);
    }
}
