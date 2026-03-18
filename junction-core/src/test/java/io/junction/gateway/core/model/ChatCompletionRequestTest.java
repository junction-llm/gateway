package io.junction.gateway.core.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionRequestTest {

    @Test
    void hasImagePartsTrueWhenImageUrlIsIncluded() {
        var message = new ChatCompletionRequest.Message(
            "user",
            List.of(
                ChatCompletionRequest.ContentPart.text("Describe this image"),
                ChatCompletionRequest.ContentPart.imageUrl("https://example.com/image.png")
            )
        );

        assertTrue(message.hasImageParts());
        assertEquals(List.of("https://example.com/image.png"), message.imageUrls());
    }

    @Test
    void hasImagePartsFalseWhenOnlyTextExists() {
        var message = new ChatCompletionRequest.Message("user", "Hello");

        assertFalse(message.hasImageParts());
        assertTrue(message.imageUrls().isEmpty());
    }

    @Test
    void getTextContentSkipsImageParts() {
        var message = new ChatCompletionRequest.Message(
            "user",
            List.of(
                ChatCompletionRequest.ContentPart.text("Question:"),
                ChatCompletionRequest.ContentPart.imageUrl("data:image/png;base64,aGVsbG8="),
                ChatCompletionRequest.ContentPart.text("Can you answer?")
            )
        );

        assertEquals("Question: Can you answer?", message.getTextContent());
    }
}
