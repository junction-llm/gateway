package io.junction.samples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

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

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mockMvc;

    @PostConstruct
    void setUpMockMvc() {
        this.mockMvc = webAppContextSetup(webApplicationContext)
            .addFilters(springSecurityFilterChain)
            .build();
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

    @Test
    void streamsThinkingFirstResponsesWithoutLeakingThinking() throws Exception {
        backend.setChatResponse("""
            {"model":"test-model","message":{"thinking":"reasoning step"},"done":false}
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
            .andExpect(content().string(containsString("[DONE]")))
            .andExpect(content().string(containsString("Hello")))
            .andExpect(content().string(not(containsString("reasoning step"))))
            .andExpect(content().string(not(containsString("\"thinking\""))));
    }

    @Test
    void returnsEmbeddingsResponse() throws Exception {
        backend.setEmbeddingResponse("""
            {"model":"embeddinggemma","embeddings":[[0.1,0.2,0.3]],"prompt_eval_count":12}
            """);

        mockMvc.perform(post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "embeddinggemma",
                      "input": "Hello"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.model").value("embeddinggemma"))
            .andExpect(jsonPath("$.data[0].object").value("embedding"))
            .andExpect(jsonPath("$.data[0].index").value(0))
            .andExpect(jsonPath("$.data[0].embedding[0]").value(0.1))
            .andExpect(jsonPath("$.usage.prompt_tokens").value(12))
            .andExpect(jsonPath("$.usage.total_tokens").value(12));
    }

    @Test
    void returnsBase64EmbeddingsWhenRequested() throws Exception {
        backend.setEmbeddingResponse("""
            {"model":"embeddinggemma","embeddings":[[0.1,0.2,0.3]],"prompt_eval_count":12}
            """);

        mockMvc.perform(post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "embeddinggemma",
                      "input": "Hello",
                      "encoding_format": "base64"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].embedding").value("zczMPc3MTD6amZk+"))
            .andExpect(jsonPath("$.usage.prompt_tokens").value(12));
    }

    @Test
    void rejectsUnsupportedEmbeddingEncodingFormat() throws Exception {
        mockMvc.perform(post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "embeddinggemma",
                      "input": "Hello",
                      "encoding_format": "hex"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request"))
            .andExpect(jsonPath("$.error.message").value(containsString("encoding_format")));
    }

    @Test
    void rejectsNonStringEmbeddingArrays() throws Exception {
        mockMvc.perform(post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "embeddinggemma",
                      "input": [1, 2, 3]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request"))
            .andExpect(jsonPath("$.error.message").value(containsString("array of strings")));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        StubOllamaBackend stubOllamaBackend() {
            return new StubOllamaBackend();
        }

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SdkTracerProviderBuilderCustomizer testSpanExporterCustomizer(InMemorySpanExporter inMemorySpanExporter) {
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(inMemorySpanExporter));
        }

        @Bean
        @Primary
        Router router(StubOllamaBackend backend, GatewayTelemetry telemetry, GatewayTracing tracing) {
            return new RoundRobinRouter(
                List.of(new OllamaProvider(backend.baseUrl(), "test-model", telemetry, tracing)),
                telemetry,
                tracing
            );
        }
    }

    static class StubOllamaBackend {
        private final AtomicReference<String> chatResponse = new AtomicReference<>("""
            {"model":"test-model","message":{"content":"Hello"},"done":false}
            {"model":"test-model","message":{"content":" world"},"done":true}
            """);
        private final AtomicReference<String> embeddingResponse = new AtomicReference<>("""
            {"model":"embeddinggemma","embeddings":[[0.1,0.2,0.3]],"prompt_eval_count":12}
            """);
        private final AtomicInteger chatStatus = new AtomicInteger(200);
        private final AtomicInteger embeddingStatus = new AtomicInteger(200);
        private final AtomicReference<String> lastChatTraceparent = new AtomicReference<>();
        private final AtomicReference<String> lastChatTracestate = new AtomicReference<>();
        private final AtomicReference<String> lastEmbeddingTraceparent = new AtomicReference<>();

        private HttpServer server;

        @PostConstruct
        void start() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/api/tags", this::handleTags);
                server.createContext("/api/chat", this::handleChat);
                server.createContext("/api/embed", this::handleEmbed);
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

        void setChatStatus(int statusCode) {
            chatStatus.set(statusCode);
        }

        void setEmbeddingResponse(String responseBody) {
            embeddingResponse.set(responseBody);
        }

        void setEmbeddingStatus(int statusCode) {
            embeddingStatus.set(statusCode);
        }

        String lastChatTraceparent() {
            return lastChatTraceparent.get();
        }

        String lastChatTracestate() {
            return lastChatTracestate.get();
        }

        String lastEmbeddingTraceparent() {
            return lastEmbeddingTraceparent.get();
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
            lastChatTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            lastChatTracestate.set(exchange.getRequestHeaders().getFirst("tracestate"));
            byte[] response = chatResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(chatStatus.get(), response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }

        private void handleEmbed(HttpExchange exchange) throws IOException {
            lastEmbeddingTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            byte[] response = embeddingResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(embeddingStatus.get(), response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }
    }
}
