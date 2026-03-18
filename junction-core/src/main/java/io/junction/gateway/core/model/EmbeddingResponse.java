package io.junction.gateway.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingResponse(
    String object,
    List<Data> data,
    String model,
    Usage usage
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(
        String object,
        Object embedding,
        int index
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("total_tokens") int totalTokens
    ) {}

    public static EmbeddingResponse of(String model, List<List<Double>> embeddings, int promptTokens) {
        return of(model, embeddings, promptTokens, null);
    }

    public static EmbeddingResponse of(String model, List<List<Double>> embeddings, int promptTokens, String encodingFormat) {
        boolean useBase64 = "base64".equals(encodingFormat);
        List<Data> data = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            List<Double> vector = List.copyOf(embeddings.get(i));
            Object payload = useBase64 ? encodeBase64(vector) : vector;
            data.add(new Data("embedding", payload, i));
        }

        return new EmbeddingResponse(
            "list",
            List.copyOf(data),
            model,
            new Usage(promptTokens, promptTokens)
        );
    }

    private static String encodeBase64(List<Double> embedding) {
        ByteBuffer buffer = ByteBuffer
            .allocate(embedding.size() * Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

        for (Double value : embedding) {
            buffer.putFloat(value.floatValue());
        }

        return Base64.getEncoder().encodeToString(buffer.array());
    }
}
