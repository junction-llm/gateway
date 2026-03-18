package io.junction.gateway.core.model;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class EmbeddingRequestDeserializer extends ValueDeserializer<EmbeddingRequest> {

    @Override
    public EmbeddingRequest deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonNode node = ctxt.readTree(p);

        String model = readOptionalText(node.get("model"));
        List<String> input = deserializeInput(node.get("input"));
        String encodingFormat = readOptionalText(node.get("encoding_format"));
        Integer dimensions = readOptionalInteger(node.get("dimensions"));
        String user = readOptionalText(node.get("user"));

        return new EmbeddingRequest(model, input, encodingFormat, dimensions, user);
    }

    private List<String> deserializeInput(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }

        if (node.isTextual()) {
            return List.of(node.asText());
        }

        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode element : node) {
                if (!element.isTextual()) {
                    throw new IllegalArgumentException(
                        "Embeddings input must be a string or an array of strings."
                    );
                }
                values.add(element.asText());
            }
            return List.copyOf(values);
        }

        throw new IllegalArgumentException(
            "Embeddings input must be a string or an array of strings."
        );
    }

    private String readOptionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Expected a string value.");
        }
        return node.asText();
    }

    private Integer readOptionalInteger(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("Expected an integer value.");
        }
        return node.asInt();
    }
}
