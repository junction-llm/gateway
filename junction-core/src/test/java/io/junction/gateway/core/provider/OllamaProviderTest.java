package io.junction.gateway.core.provider;

import io.junction.gateway.core.exception.ProviderException;
import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OllamaProviderTest {

    private static final String SAMPLE_IMAGE_BASE64 = "aGVsbG8=";

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
    void testFormatMessages_SingleMessage() {
        var messages = List.of(new ChatCompletionRequest.Message("user", "Hello"));
        List<Map<String, Object>> result = invokeFormatMessages(messages);

        assertEquals(1, result.size());
        assertEquals("user", result.getFirst().get("role"));
        assertEquals("Hello", result.getFirst().get("content"));
        assertFalse(result.getFirst().containsKey("images"));
    }

    @Test
    void testFormatMessages_MultipleMessages() {
        var messages = List.of(
            new ChatCompletionRequest.Message("system", "You are a helpful assistant"),
            new ChatCompletionRequest.Message("user", "Hello"),
            new ChatCompletionRequest.Message("assistant", "Hi there!")
        );
        List<Map<String, Object>> result = invokeFormatMessages(messages);

        assertEquals(3, result.size());
        assertEquals("system", result.get(0).get("role"));
        assertEquals("user", result.get(1).get("role"));
        assertEquals("assistant", result.get(2).get("role"));
        assertEquals("You are a helpful assistant", result.get(0).get("content"));
        assertEquals("Hello", result.get(1).get("content"));
        assertEquals("Hi there!", result.get(2).get("content"));
    }

    @Test
    void testFormatMessages_WithImageInput() {
        var messages = List.of(new ChatCompletionRequest.Message(
            "user",
            List.of(
                ChatCompletionRequest.ContentPart.text("Describe this image"),
                ChatCompletionRequest.ContentPart.imageUrl("data:image/png;base64," + SAMPLE_IMAGE_BASE64)
            )
        ));

        List<Map<String, Object>> result = invokeFormatMessages(messages);
        assertEquals(1, result.size());
        assertEquals("user", result.getFirst().get("role"));
        assertEquals("Describe this image", result.getFirst().get("content"));
        assertTrue(result.getFirst().containsKey("images"));

        @SuppressWarnings("unchecked")
        var images = (List<String>) result.getFirst().get("images");
        assertEquals(List.of(SAMPLE_IMAGE_BASE64), images);
    }

    @Test
    void testFormatMessages_WithSpecialCharacters() {
        var messages = List.of(
            new ChatCompletionRequest.Message("user", "Hello\nWorld\t\"Test\"")
        );
        List<Map<String, Object>> result = invokeFormatMessages(messages);

        assertEquals("Hello\nWorld\t\"Test\"", result.getFirst().get("content"));
    }

    @Test
    void testResponseAdapter_ReturnsOpenAIAdapterGatherer() {
        var gatherer = provider.responseAdapter();
        assertNotNull(gatherer);
    }

    @Test
    void testParseNdJson_CapturesThinkingOnlyChunk() {
        var responses = invokeParseNdJson("""
            {"model":"kimi-k2.5","message":{"thinking":"Working through the answer"},"done":false}
            """);

        var response = assertInstanceOf(ProviderResponse.OllamaResponse.class, responses.getFirst());
        assertEquals("", response.content());
        assertEquals("Working through the answer", response.thinking());
        assertEquals("kimi-k2.5", response.model());
        assertFalse(response.done());
    }

    @Test
    void testResolveImageToBase64_AcceptsDataUri() throws Exception {
        assertEquals(SAMPLE_IMAGE_BASE64, invokeNormalizeImage("data:image/png;base64," + SAMPLE_IMAGE_BASE64));
    }

    @Test
    void testResolveImageToBase64_RejectsUnsupportedScheme() {
        InvocationTargetException ex = assertThrows(
            InvocationTargetException.class,
            () -> invokeNormalizeImage("file:///tmp/image.png")
        );
        assertTrue(ex.getCause() instanceof ProviderException);
    }

    @Test
    void testResolveImageToBase64_RemoteImageUrl() throws Exception {
        var imageBytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = "/image.png";

        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, imageBytes.length);
            exchange.getResponseBody().write(imageBytes);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + path;
            String normalized = invokeNormalizeImage(imageUrl);
            assertEquals(Base64.getEncoder().encodeToString(imageBytes), normalized);
        } finally {
            server.stop(0);
        }
    }

    private List<Map<String, Object>> invokeFormatMessages(List<ChatCompletionRequest.Message> messages) {
        try {
            var method = OllamaProvider.class.getDeclaredMethod("formatMessages", List.class);
            method.setAccessible(true);
            return (List<Map<String, Object>>) method.invoke(provider, messages);
        } catch (Exception e) {
            fail("Failed to invoke formatMessages: " + e.getMessage());
            return null;
        }
    }

    private String invokeNormalizeImage(String source) throws InvocationTargetException {
        try {
            var method = OllamaProvider.class.getDeclaredMethod("normalizeImageInput", String.class);
            method.setAccessible(true);
            return (String) method.invoke(provider, source);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to invoke normalizeImageInput", e);
        } catch (InvocationTargetException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke normalizeImageInput", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ProviderResponse> invokeParseNdJson(String payload) {
        try {
            var method = OllamaProvider.class.getDeclaredMethod("parseNdJson", java.io.InputStream.class, UUID.class);
            method.setAccessible(true);

            try (Stream<ProviderResponse> stream = (Stream<ProviderResponse>) method.invoke(
                provider,
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)),
                UUID.randomUUID()
            )) {
                return stream.toList();
            }
        } catch (Exception e) {
            fail("Failed to invoke parseNdJson: " + e.getMessage());
            return List.of();
        }
    }
}
