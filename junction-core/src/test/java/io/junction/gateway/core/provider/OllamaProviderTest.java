package io.junction.gateway.core.provider;

import io.junction.gateway.core.context.RequestContext;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;
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
    void testResolveImageToBase64_RejectsOversizedDataUrlBeforeDecode() throws Exception {
        var limitedProvider = new OllamaProvider(
            "http://localhost:9999",
            "llama3.1",
            null,
            null,
            3,
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            100,
            true
        );

        InvocationTargetException ex = assertThrows(
            InvocationTargetException.class,
            () -> invokeNormalizeImage(limitedProvider, "data:image/png;base64," + Base64.getEncoder().encodeToString("four".getBytes(StandardCharsets.UTF_8)))
        );
        assertTrue(ex.getCause() instanceof ProviderException);
        assertTrue(ex.getCause().getMessage().contains("maximum size"));
    }

    @Test
    void testChatBulkheadRejectsImageRequestBeforeRemoteImageFetch() throws Exception {
        var firstResponseStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseFirstResponse = new java.util.concurrent.CountDownLatch(1);
        var chatRequestCount = new java.util.concurrent.atomic.AtomicInteger();
        var imageRequestCount = new java.util.concurrent.atomic.AtomicInteger();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            chatRequestCount.incrementAndGet();
            byte[] body = "{\"message\":{\"content\":\"hello\"},\"done\":false}\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
                responseBody.flush();
                firstResponseStarted.countDown();
                releaseFirstResponse.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/image.png", exchange -> {
            imageRequestCount.incrementAndGet();
            byte[] imageBytes = "image".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, imageBytes.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(imageBytes);
            }
        });
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();

        try {
            var limitedProvider = new OllamaProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "llama3.1",
                null,
                null,
                20 * 1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                1,
                true
            );
            var firstRequest = new ChatCompletionRequest(
                "llama3.1",
                List.of(new ChatCompletionRequest.Message("user", "Hello")),
                true,
                null,
                null
            );
            var imageRequest = new ChatCompletionRequest(
                "llama3.1",
                List.of(new ChatCompletionRequest.Message(
                    "user",
                    List.of(
                        ChatCompletionRequest.ContentPart.text("describe"),
                        ChatCompletionRequest.ContentPart.imageUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/image.png")
                    )
                )),
                true,
                null,
                null
            );

            var context = new RequestContext.Context(UUID.randomUUID(), null, firstRequest.model(), Instant.now());
            ScopedValue.where(RequestContext.key(), context).run(() -> {
                Stream<ProviderResponse> firstStream = limitedProvider.execute(firstRequest);
                try {
                    assertTrue(firstResponseStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    var secondResponses = limitedProvider.execute(imageRequest).toList();
                    assertEquals(1, secondResponses.size());
                    var error = assertInstanceOf(ProviderResponse.ErrorResponse.class, secondResponses.getFirst());
                    assertEquals(503, error.code());
                    assertEquals(0, imageRequestCount.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting for provider response");
                } finally {
                    releaseFirstResponse.countDown();
                    firstStream.close();
                }
            });
            assertEquals(1, chatRequestCount.get());
        } finally {
            releaseFirstResponse.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
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
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, imageBytes.length);
            exchange.getResponseBody().write(imageBytes);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + path;
            String normalized = invokeNormalizeImage(providerAllowingPrivateRemoteImages(), imageUrl);
            assertEquals(Base64.getEncoder().encodeToString(imageBytes), normalized);
        } finally {
            server.stop(0);
        }
    }



    @Test
    void testResolveImageToBase64_RemoteImageUrlAcceptsContentTypeParametersAndCase() throws Exception {
        var imageBytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = "/image.png";

        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "Image/PNG; charset=binary");
            exchange.sendResponseHeaders(200, imageBytes.length);
            exchange.getResponseBody().write(imageBytes);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + path;
            String normalized = invokeNormalizeImage(providerAllowingPrivateRemoteImages(), imageUrl);
            assertEquals(Base64.getEncoder().encodeToString(imageBytes), normalized);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResolveImageToBase64_RejectsMissingRemoteImageContentType() throws Exception {
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
            InvocationTargetException ex = assertThrows(
                InvocationTargetException.class,
                () -> invokeNormalizeImage(providerAllowingPrivateRemoteImages(), imageUrl)
            );
            assertTrue(ex.getCause() instanceof ProviderException);
            assertTrue(ex.getCause().getMessage().contains("image/* content type"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResolveImageToBase64_RejectsOversizedRemoteImageContentLength() throws Exception {
        var imageBytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = "/image.png";

        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(imageBytes.length));
            exchange.sendResponseHeaders(200, imageBytes.length);
            exchange.getResponseBody().write(imageBytes);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + path;
            var limitedProvider = new OllamaProvider(
                "http://localhost:9999",
                "llama3.1",
                null,
                null,
                imageBytes.length - 1,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                100,
                true
            );
            InvocationTargetException ex = assertThrows(
                InvocationTargetException.class,
                () -> invokeNormalizeImage(limitedProvider, imageUrl)
            );
            assertTrue(ex.getCause() instanceof ProviderException);
            assertTrue(ex.getCause().getMessage().contains("maximum size"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResolveImageToBase64_RejectsOversizedRemoteImageWhileStreaming() throws Exception {
        var imageBytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = "/image.png";

        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(imageBytes);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + path;
            var limitedProvider = new OllamaProvider(
                "http://localhost:9999",
                "llama3.1",
                null,
                null,
                imageBytes.length - 1,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                100,
                true
            );
            InvocationTargetException ex = assertThrows(
                InvocationTargetException.class,
                () -> invokeNormalizeImage(limitedProvider, imageUrl)
            );
            assertTrue(ex.getCause() instanceof ProviderException);
            assertTrue(ex.getCause().getMessage().contains("maximum size"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResolveImageToBase64_RejectsPrivateRemoteImageUrlByDefault() throws Exception {
        InvocationTargetException ex = assertThrows(
            InvocationTargetException.class,
            () -> invokeNormalizeImage("http://127.0.0.1:65535/image.png")
        );
        assertTrue(ex.getCause() instanceof ProviderException);
        assertTrue(ex.getCause().getMessage().contains("private or local network address"));
    }

    @Test
    void testConstructorRejectsInvalidMaxRemoteImageBytes() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new OllamaProvider("http://localhost:9999", "llama3.1", null, null, 0)
        );
    }

    @Test
    void testChatBulkheadRejectsConcurrentRequestAndReleasesOnClose() throws Exception {
        var firstResponseStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseFirstResponse = new java.util.concurrent.CountDownLatch(1);
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();

        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"message\":{\"content\":\"hello\"},\"done\":false}\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
                responseBody.flush();
                firstResponseStarted.countDown();
                releaseFirstResponse.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();

        try {
            var limitedProvider = new OllamaProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "llama3.1",
                null,
                null,
                20 * 1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                1
            );
            var request = new ChatCompletionRequest(
                "llama3.1",
                List.of(new ChatCompletionRequest.Message("user", "Hello")),
                true,
                null,
                null
            );

            var context = new RequestContext.Context(UUID.randomUUID(), null, request.model(), Instant.now());
            ScopedValue.where(RequestContext.key(), context).run(() -> {
                Stream<ProviderResponse> firstStream = limitedProvider.execute(request);
                try {
                    assertTrue(firstResponseStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting for provider response");
                }

                try {
                    var secondResponses = limitedProvider.execute(request).toList();
                    assertEquals(1, secondResponses.size());
                    assertInstanceOf(ProviderResponse.ErrorResponse.class, secondResponses.getFirst());
                    var error = (ProviderResponse.ErrorResponse) secondResponses.getFirst();
                    assertEquals(503, error.code());
                } finally {
                    releaseFirstResponse.countDown();
                    firstStream.close();
                }

                try (var thirdStream = limitedProvider.execute(request)) {
                    assertTrue(StreamSupport.stream(thirdStream.spliterator(), false).findFirst().isPresent());
                }
                assertEquals(2, requestCount.get());
            });
        } finally {
            releaseFirstResponse.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void capsChatHttpErrorBodyInReturnedError() throws Exception {
        var largeError = "x".repeat(80 * 1024);
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var response = largeError.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        var executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();

        try {
            var provider = new OllamaProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "llama3.1",
                null,
                null,
                20 * 1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                1,
                true
            );
            var request = new ChatCompletionRequest(
                "llama3.1",
                List.of(new ChatCompletionRequest.Message("user", "Hello")),
                false,
                null,
                null
            );
            var context = new RequestContext.Context(UUID.randomUUID(), null, request.model(), Instant.now());

            ScopedValue.where(RequestContext.key(), context).run(() -> {
                try (var stream = provider.execute(request)) {
                    var responses = stream.toList();
                    assertEquals(1, responses.size());
                    var error = assertInstanceOf(ProviderResponse.ErrorResponse.class, responses.getFirst());
                    assertEquals(500, error.code());
                    assertTrue(error.error().contains("truncated after 65536 bytes"));
                    assertTrue(error.error().length() < largeError.length());
                }
            });
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void testConstructorRejectsInvalidMaxConcurrentRequests() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new OllamaProvider(
                "http://localhost:9999",
                "llama3.1",
                null,
                null,
                20 * 1024 * 1024,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                0
            )
        );
    }

    private OllamaProvider providerAllowingPrivateRemoteImages() {
        return new OllamaProvider(
            "http://localhost:9999",
            "llama3.1",
            null,
            null,
            20 * 1024 * 1024,
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            100,
            true
        );
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
        return invokeNormalizeImage(provider, source);
    }

    private String invokeNormalizeImage(OllamaProvider provider, String source) throws InvocationTargetException {
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
