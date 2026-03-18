package io.junction.gateway.core.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingRequestDeserializerTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        SimpleModule module = new SimpleModule("EmbeddingRequestTestModule");
        module.addDeserializer(EmbeddingRequest.class, new EmbeddingRequestDeserializer());

        mapper = JsonMapper.builder()
            .addModule(module)
            .build();
    }

    @Test
    void deserializesSingleStringInput() throws Exception {
        var request = mapper.readValue("""
            {
              "model": "embeddinggemma",
              "input": "hello world"
            }
            """, EmbeddingRequest.class);

        assertEquals("embeddinggemma", request.model());
        assertEquals(1, request.input().size());
        assertEquals("hello world", request.input().getFirst());
    }

    @Test
    void deserializesArrayInput() throws Exception {
        var request = mapper.readValue("""
            {
              "model": "embeddinggemma",
              "input": ["hello", "world"],
              "encoding_format": "float",
              "user": "abc123"
            }
            """, EmbeddingRequest.class);

        assertEquals("embeddinggemma", request.model());
        assertEquals(2, request.input().size());
        assertEquals("float", request.encodingFormat());
        assertEquals("abc123", request.user());
    }

    @Test
    void rejectsTokenArrayInput() {
        var exception = assertThrows(Exception.class, () -> mapper.readValue("""
            {
              "model": "embeddinggemma",
              "input": [[1, 2, 3]]
            }
            """, EmbeddingRequest.class));

        assertTrue(exception.getMessage().contains("string or an array of strings"));
    }

    @Test
    void rejectsMixedArrayInput() {
        var exception = assertThrows(Exception.class, () -> mapper.readValue("""
            {
              "model": "embeddinggemma",
              "input": ["hello", 42]
            }
            """, EmbeddingRequest.class));

        assertTrue(exception.getMessage().contains("string or an array of strings"));
    }

    @Test
    void rejectsNonIntegerDimensions() {
        var exception = assertThrows(Exception.class, () -> mapper.readValue("""
            {
              "model": "embeddinggemma",
              "input": "hello",
              "dimensions": "wide"
            }
            """, EmbeddingRequest.class));

        assertTrue(exception.getMessage().contains("integer value"));
    }
}
