package io.junction.samples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = {Application.class, GatewayIntegrationTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "junction.providers.ollama.enabled=false",
        "junction.providers.gemini.enabled=false",
        "junction.security.api-key.required=false",
        "junction.security.ip-rate-limit.enabled=false"
    }
)
class GatewayIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private StubOllamaBackend backend;

    private MockMvc mockMvc;

    @PostConstruct
    void setUpMockMvc() {
        this.mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void returnsNonStreamingChatCompletion() throws Exception {
        backend.setChatResponse("""
            {"model":"test-model","message":{"content":"Hello"},"done":false}
            {"model":"test-model","message":{"content":" world"},"done":true}
            """);

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "test-model",
                      "messages": [{"role": "user", "content": "Hello"}],
                      "stream": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.object").value("chat.completion"))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
            .andExpect(jsonPath("$.choices[0].message.content").value("Hello world"));
    }

    @Test
    void streamsSseResponses() throws Exception {
        backend.setChatResponse("""
            {"model":"test-model","message":{"content":"Hello"},"done":false}
            {"model":"test-model","message":{"content":""},"done":true}
            """);

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "model": "test-model",
                      "messages": [{"role": "user", "content": "Hello"}],
                      "stream": true
                    }
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        result.getAsyncResult(5_000);

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(content().string(containsString("data:")))
            .andExpect(content().string(containsString("[DONE]")))
            .andExpect(content().string(containsString("Hello")));
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

        @PostConstruct
        void start() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/api/tags", this::handleTags);
                server.createContext("/api/chat", this::handleChat);
                server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
                server.start();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start stub Ollama backend", e);
            }
        }

        @PreDestroy
        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
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
