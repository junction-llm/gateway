package io.junction.samples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

abstract class AbstractGatewayResponseLoggingIntegrationTest {

    private static final Path LOG_ROOT = Path.of("logs");

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected StubOllamaBackend backend;

    protected MockMvc mockMvc;

    @PostConstruct
    void setUpMockMvc() {
        this.mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    protected Set<Path> snapshotLogFiles() throws IOException {
        if (!Files.exists(LOG_ROOT)) {
            return Set.of();
        }

        try (var stream = Files.walk(LOG_ROOT)) {
            return stream
                .filter(Files::isRegularFile)
                .collect(Collectors.toSet());
        }
    }

    protected String awaitNewLogFileContaining(Set<Path> before, String expectedText) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (Path file : currentLogFiles()) {
                if (before.contains(file)) {
                    continue;
                }

                String content = Files.readString(file);
                if (content.contains(expectedText)) {
                    return content;
                }
            }
            Thread.sleep(100);
        }

        fail("Timed out waiting for a new log file containing: " + expectedText);
        return "";
    }

    protected long countOccurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1L;
    }

    private List<Path> currentLogFiles() throws IOException {
        if (!Files.exists(LOG_ROOT)) {
            return List.of();
        }

        try (var stream = Files.walk(LOG_ROOT)) {
            return stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparingLong(this::lastModifiedSafe).reversed())
                .toList();
        }
    }

    private long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    protected static String chatRequest(boolean stream) {
        return """
            {
              "model": "test-model",
              "messages": [{"role": "user", "content": "Hello"}],
              "stream": %s
            }
            """.formatted(stream);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        StubOllamaBackend stubOllamaBackend() {
            return new StubOllamaBackend();
        }

        @Bean
        @Primary
        Router router(StubOllamaBackend backend) {
            return new RoundRobinRouter(List.of(new OllamaProvider(backend.baseUrl(), "test-model")));
        }
    }

    static class StubOllamaBackend {
        private final AtomicReference<String> chatResponse = new AtomicReference<>("""
            {"model":"test-model","message":{"content":"Hello"},"done":false}
            {"model":"test-model","message":{"content":" world"},"done":true}
            """);

        private HttpServer server;
        private int port;

        @PostConstruct
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/tags", this::handleTags);
            server.createContext("/api/chat", this::handleChat);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            port = server.getAddress().getPort();
        }

        @PreDestroy
        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        String baseUrl() {
            return "http://localhost:" + port;
        }

        void setChatResponse(String responseBody) {
            chatResponse.set(responseBody);
        }

        private void handleTags(HttpExchange exchange) throws IOException {
            byte[] response = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }

        private void handleChat(HttpExchange exchange) throws IOException {
            byte[] response = chatResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }
    }
}

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    classes = {Application.class, AbstractGatewayResponseLoggingIntegrationTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "junction.providers.ollama.enabled=false",
        "junction.providers.gemini.enabled=false",
        "junction.security.api-key.required=false",
        "junction.security.ip-rate-limit.enabled=false",
        "junction.logging.chat-response.enabled=true"
    }
)
class GatewayResponseLoggingEnabledIntegrationTest extends AbstractGatewayResponseLoggingIntegrationTest {

    @Test
    void logsNonStreamingResponseBodyOnlyToPerRequestFile(CapturedOutput output) throws Exception {
        String uniqueResponse = "response-log-json-only-unique";
        backend.setChatResponse("""
            {"model":"test-model","message":{"content":"response-log-"},"done":false}
            {"model":"test-model","message":{"content":"json-only-unique"},"done":true}
            """);

        Set<Path> before = snapshotLogFiles();

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(chatRequest(false)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.choices[0].message.content").value(uniqueResponse));

        String logContent = awaitNewLogFileContaining(before, uniqueResponse);
        assertThat(logContent).contains("Chat response body:");
        assertThat(logContent).contains("\"object\":\"chat.completion\"");
        assertThat(logContent).contains(uniqueResponse);
        assertThat(output.getAll()).doesNotContain(uniqueResponse);
    }

    @Test
    void logsOneAssembledStreamingResponseBodyOnlyToPerRequestFile(CapturedOutput output) throws Exception {
        String uniqueResponse = "stream-assembled-response-unique";
        backend.setChatResponse("""
            {"model":"test-model","message":{"content":"stream-"},"done":false}
            {"model":"test-model","message":{"content":"assembled-"},"done":false}
            {"model":"test-model","message":{"content":"response-unique"},"done":true}
            """);

        Set<Path> before = snapshotLogFiles();

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(chatRequest(true)))
            .andExpect(request().asyncStarted())
            .andReturn();

        result.getAsyncResult(5_000);

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(content().string(containsString("[DONE]")))
            .andExpect(content().string(containsString("stream-")))
            .andExpect(content().string(containsString("response-unique")));

        String logContent = awaitNewLogFileContaining(before, uniqueResponse);
        assertThat(logContent).contains(uniqueResponse);
        assertThat(countOccurrences(logContent, "Chat response body:")).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(uniqueResponse);
    }
}

@SpringBootTest(
    classes = {Application.class, AbstractGatewayResponseLoggingIntegrationTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "junction.providers.ollama.enabled=false",
        "junction.providers.gemini.enabled=false",
        "junction.security.api-key.required=false",
        "junction.security.ip-rate-limit.enabled=false",
        "junction.logging.chat-response.enabled=false"
    }
)
class GatewayResponseLoggingDisabledIntegrationTest extends AbstractGatewayResponseLoggingIntegrationTest {

    @Test
    void doesNotLogChatResponseBodyWhenDisabled() throws Exception {
        String uniqueResponse = "disabled-chat-response-body-unique";
        backend.setChatResponse("""
            {"model":"test-model","message":{"content":"disabled-chat-"},"done":false}
            {"model":"test-model","message":{"content":"response-body-unique"},"done":true}
            """);

        Set<Path> before = snapshotLogFiles();

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(chatRequest(false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value(uniqueResponse));

        String logContent = awaitNewLogFileContaining(before, "=== INCOMING REQUEST [JSON] ===");
        assertThat(logContent).contains("=== INCOMING REQUEST [JSON] ===");
        assertThat(logContent).doesNotContain("Chat response body:");
        assertThat(logContent).doesNotContain(uniqueResponse);
    }
}
