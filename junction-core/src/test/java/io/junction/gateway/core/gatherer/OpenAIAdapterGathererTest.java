package io.junction.gateway.core.gatherer;

import io.junction.gateway.core.model.ChatCompletionChunk;
import io.junction.gateway.core.model.ProviderResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIAdapterGathererTest {

    private final OpenAIAdapterGatherer gatherer = new OpenAIAdapterGatherer("fallback-model");

    @Test
    void thinkingOnlyChunkDoesNotEmitClientVisibleChunk() {
        var chunks = gather(
            new ProviderResponse.OllamaResponse("", "Thinking...", "test-model", false, null, null)
        );

        assertTrue(chunks.isEmpty());
    }

    @Test
    void thinkingBeforeContentStillEmitsNormalChunks() {
        var chunks = gather(
            new ProviderResponse.OllamaResponse("", "Thinking...", "test-model", false, null, null),
            new ProviderResponse.OllamaResponse("Hello", "", "test-model", false, null, null),
            new ProviderResponse.OllamaResponse("", "", "test-model", true, null, new ProviderResponse.Usage(3, 5, 8))
        );

        assertEquals(2, chunks.size());
        assertEquals("test-model", chunks.get(0).model());
        assertEquals("Hello", chunks.get(0).choices().getFirst().delta().content());
        assertNull(chunks.get(0).choices().getFirst().finishReason());
        assertEquals("stop", chunks.get(1).choices().getFirst().finishReason());
        assertNotNull(chunks.get(1).usage());
        assertEquals(8, chunks.get(1).usage().totalTokens());
    }

    @Test
    void thinkingOnlyFirstFrameStillSetsFinalModel() {
        var chunks = gather(
            new ProviderResponse.OllamaResponse("", "Thinking...", "test-model", false, null, null),
            new ProviderResponse.OllamaResponse("", "", "test-model", true, null, null)
        );

        assertEquals(1, chunks.size());
        assertEquals("test-model", chunks.getFirst().model());
        assertEquals("stop", chunks.getFirst().choices().getFirst().finishReason());
        assertEquals("", chunks.getFirst().choices().getFirst().delta().content());
    }

    @Test
    void toolCallOnlyFirstFrameUsesProviderModel() {
        var toolCalls = List.of(
            new ProviderResponse.ToolCall(
                "call_1",
                0,
                new ProviderResponse.FunctionCall("lookup", "{\"city\":\"Toronto\"}")
            )
        );

        var chunks = gather(
            new ProviderResponse.OllamaResponse("", "", "test-model", false, toolCalls, null)
        );

        assertEquals(1, chunks.size());
        ChatCompletionChunk chunk = chunks.getFirst();
        assertEquals("test-model", chunk.model());
        assertNull(chunk.choices().getFirst().delta().content());
        assertFalse(chunk.choices().getFirst().delta().toolCalls().isEmpty());
        assertEquals("lookup", chunk.choices().getFirst().delta().toolCalls().getFirst().function().name());
    }

    private List<ChatCompletionChunk> gather(ProviderResponse... responses) {
        try (Stream<ChatCompletionChunk> stream = Stream.of(responses).gather(gatherer)) {
            return stream.toList();
        }
    }
}
