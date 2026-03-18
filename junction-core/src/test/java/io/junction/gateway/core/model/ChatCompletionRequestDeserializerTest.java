package io.junction.gateway.core.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionRequestDeserializerTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        SimpleModule module = new SimpleModule("ChatCompletionRequestTestModule");
        module.addDeserializer(ChatCompletionRequest.class, new ChatCompletionRequestDeserializer());
        module.addDeserializer(ChatCompletionRequest.Message.class, new MessageDeserializer());

        mapper = JsonMapper.builder()
            .addModule(module)
            .build();
    }

    @Test
    void parsesMixedTextAndImageContentArray() throws Exception {
        var request = mapper.readValue("""
            {
              "model": "qwen3.5",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "Describe this image"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/image.png"}}
                  ]
                }
              ]
            }
            """, ChatCompletionRequest.class);

        var message = request.messages().getFirst();

        assertEquals("user", message.role());
        assertEquals(2, message.content().size());
        assertEquals("text", message.content().get(0).type());
        assertEquals("Describe this image", message.content().get(0).text());
        assertEquals("image_url", message.content().get(1).type());
        assertEquals("https://example.com/image.png", message.content().get(1).imageUrl().url());
        assertTrue(message.hasImageParts());
        assertEquals(List.of("https://example.com/image.png"), message.imageUrls());
    }

    @Test
    void parsesTextOnlyContent() throws Exception {
        var request = mapper.readValue("""
            {
              "model": "llama3.1",
              "messages": [
                {
                  "role": "user",
                  "content": "Hello"
                }
              ]
            }
            """, ChatCompletionRequest.class);

        var message = request.messages().getFirst();
        assertFalse(message.hasImageParts());
        assertTrue(message.imageUrls().isEmpty());
        assertEquals("Hello", message.getTextContent());
    }
}
