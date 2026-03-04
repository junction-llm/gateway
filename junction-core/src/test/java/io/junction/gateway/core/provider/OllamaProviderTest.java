package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OllamaProvider}.
 */
class OllamaProviderTest {

    private OllamaProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OllamaProvider("http://localhost:9999", "llama3.1");
    }

    @Test
    void testProviderId() {
        assertEquals("ollama", provider.providerId());
    }

    @Test
    void testIsHealthy_WhenUrlInvalid() {
        assertFalse(provider.isHealthy());
    }

    @Test
    void testEscapeJson_WithSimpleString() {
        String input = "Hello World";
        String result = invokeEscapeJson(input);
        assertEquals("Hello World", result);
    }

    @Test
    void testEscapeJson_WithSpecialCharacters() {
        String input = "Line1\nLine2\tTab\"Quote\\Back";
        String result = invokeEscapeJson(input);
        assertEquals("Line1\\nLine2\\tTab\\\"Quote\\\\Back", result);
    }

    @Test
    void testEscapeJson_WithControlCharacters() {
        String input = "Text\b\f\r";
        String result = invokeEscapeJson(input);
        assertEquals("Text\\b\\f\\r", result);
    }

    @Test
    void testEscapeJson_WithUnicode() {
        String input = "Hello 世界 🌍";
        String result = invokeEscapeJson(input);
        assertEquals("Hello 世界 🌍", result);
    }

    @Test
    void testEscapeJson_WithEmptyString() {
        String input = "";
        String result = invokeEscapeJson(input);
        assertEquals("", result);
    }

    @Test
    void testEscapeJson_WithNull() {
        String result = invokeEscapeJson(null);
        assertEquals("", result);
    }

    @Test
    void testFormatMessages_SingleMessage() {
        var messages = List.of(new ChatCompletionRequest.Message("user", "Hello"));
        String result = invokeFormatMessages(messages);
        
        assertTrue(result.contains("\"user\""));
        assertTrue(result.contains("\"Hello\""));
    }

    @Test
    void testFormatMessages_MultipleMessages() {
        var messages = List.of(
            new ChatCompletionRequest.Message("system", "You are a helpful assistant"),
            new ChatCompletionRequest.Message("user", "Hello"),
            new ChatCompletionRequest.Message("assistant", "Hi there!")
        );
        String result = invokeFormatMessages(messages);
        
        assertTrue(result.contains("\"system\""));
        assertTrue(result.contains("\"user\""));
        assertTrue(result.contains("\"assistant\""));
        assertTrue(result.contains("You are a helpful assistant"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("Hi there!"));
    }

    @Test
    void testFormatMessages_WithSpecialCharacters() {
        var messages = List.of(
            new ChatCompletionRequest.Message("user", "Hello\nWorld\t\"Test\"")
        );
        String result = invokeFormatMessages(messages);
        
        assertTrue(result.contains("\\n"));
        assertTrue(result.contains("\\t"));
        assertTrue(result.contains("\\\""));
    }

    @Test
    void testResponseAdapter_ReturnsOpenAIAdapterGatherer() {
        var gatherer = provider.responseAdapter();
        assertNotNull(gatherer);
    }

    private String invokeEscapeJson(String input) {
        try {
            var method = OllamaProvider.class.getDeclaredMethod("escapeJson", String.class);
            method.setAccessible(true);
            return (String) method.invoke(provider, input);
        } catch (Exception e) {
            fail("Failed to invoke escapeJson: " + e.getMessage());
            return null;
        }
    }

    private String invokeFormatMessages(List<ChatCompletionRequest.Message> messages) {
        try {
            var method = OllamaProvider.class.getDeclaredMethod("formatMessages", List.class);
            method.setAccessible(true);
            return (String) method.invoke(provider, messages);
        } catch (Exception e) {
            fail("Failed to invoke formatMessages: " + e.getMessage());
            return null;
        }
    }
}