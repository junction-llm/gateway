package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeminiProviderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GeminiProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GeminiProvider("test-api-key", "gemini-1.5-flash");
    }

    @Test
    void testProviderId() {
        assertEquals("gemini", provider.providerId());
    }

    @Test
    void testIsHealthy_WithValidApiKey() {
        var providerWithKey = new GeminiProvider("valid-api-key", "gemini-1.5-flash");
        
        assertTrue(providerWithKey.isHealthy());
    }

    @Test
    void testIsHealthy_WithEmptyApiKey() {
        var providerWithEmptyKey = new GeminiProvider("", "gemini-1.5-flash");
        
        assertFalse(providerWithEmptyKey.isHealthy());
    }

    @Test
    void testIsHealthy_WithNullApiKey() {
        var providerWithNullKey = new GeminiProvider(null, "gemini-1.5-flash");
        
        assertFalse(providerWithNullKey.isHealthy());
    }

    @Test
    void testEscapeJson_WithSimpleString() {
        String result = invokeEscapeJson("Hello World");
        assertEquals("\"Hello World\"", result);
    }

    @Test
    void testEscapeJson_WithQuotes() {
        String result = invokeEscapeJson("Say \"Hello\"");
        assertEquals("\"Say \\\"Hello\\\"\"", result);
    }

    @Test
    void testEscapeJson_WithEmptyString() {
        String result = invokeEscapeJson("");
        assertEquals("\"\"", result);
    }

    @Test
    void testEscapeJson_WithNewlines() {
        String result = invokeEscapeJson("Line1\nLine2");
        assertEquals("\"Line1\\nLine2\"", result);
    }

    @Test
    void testEscapeJson_WithUnicode() {
        String result = invokeEscapeJson("Hello 世界 🌍");
        assertEquals("\"Hello 世界 🌍\"", result);
    }

    @Test
    void testResponseAdapter_ReturnsOpenAIAdapterGatherer() {
        var gatherer = provider.responseAdapter();
        assertNotNull(gatherer);
    }

    @Test
    void testTransformToGemini_SingleUserMessage() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("user", "Hello")),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertTrue(result.contains("\"user\""));
        assertTrue(result.contains("\"Hello\""));
    }

    @Test
    void testTransformToGemini_SystemMessage() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("system", "You are helpful")),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertTrue(result.contains("\"user\""));
        assertTrue(result.contains("[System]"));
    }

    @Test
    void testTransformToGemini_AssistantMessage() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("assistant", "Hi there")),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertTrue(result.contains("\"model\""));
        assertTrue(result.contains("Hi there"));
    }

    @Test
    void testTransformToGemini_MultipleMessages() {
        var request = new ChatCompletionRequest(
            null,
            List.of(
                new ChatCompletionRequest.Message("user", "Hello"),
                new ChatCompletionRequest.Message("assistant", "Hi"),
                new ChatCompletionRequest.Message("user", "How are you?")
            ),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertTrue(result.contains("\"user\""));
        assertTrue(result.contains("\"model\""));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("Hi"));
        assertTrue(result.contains("How are you?"));
    }

    @Test
    void testTransformToGemini_Temperature() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("user", "Test")),
            true,
            0.8,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertEquals(0.8, readTemperature(result));
    }

    @Test
    void testTransformToGemini_DefaultTemperature() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("user", "Test")),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertEquals(0.3, readTemperature(result));
    }

    @Test
    void testTransformToGemini_WithSpecialCharacters() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("user", "Say \"Hello\"\nPath: C:\\temp")),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertTrue(result.contains("\\\""));
        assertTrue(result.contains("\\n"));
        assertTrue(result.contains("\\\\"));
    }

    @Test
    void testTransformToGemini_ModelName() {
        var request = new ChatCompletionRequest(
            null,
            List.of(new ChatCompletionRequest.Message("user", "Test")),
            true,
            null,
            null
        );
        
        String result = invokeTransformToGemini(request);
        
        assertNotNull(result);
        assertTrue(result.contains("\"contents\":"));
    }

    private String invokeEscapeJson(String input) {
        try {
            var method = GeminiProvider.class.getDeclaredMethod("escapeJson", String.class);
            method.setAccessible(true);
            return (String) method.invoke(provider, input);
        } catch (Exception e) {
            fail("Failed to invoke escapeJson: " + e.getMessage());
            return null;
        }
    }

    private String invokeTransformToGemini(ChatCompletionRequest request) {
        try {
            var method = GeminiProvider.class.getDeclaredMethod("transformToGemini", ChatCompletionRequest.class);
            method.setAccessible(true);
            return (String) method.invoke(provider, request);
        } catch (Exception e) {
            fail("Failed to invoke transformToGemini: " + e.getMessage());
            return null;
        }
    }

    private double readTemperature(String json) {
        try {
            return OBJECT_MAPPER.readTree(json)
                .path("generationConfig")
                .path("temperature")
                .asDouble();
        } catch (Exception e) {
            fail("Failed to parse Gemini request JSON: " + e.getMessage());
            return Double.NaN;
        }
    }
}
