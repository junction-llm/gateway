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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(
    classes = {Application.class, GatewayIpWhitelistIntegrationTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "junction.providers.ollama.enabled=true",
        "junction.providers.gemini.enabled=false",
        "junction.security.api-key.required=true",
        "junction.security.api-key.preconfigured[0].key=junc_abcdefghijklmnopqrstuvwxyz123456",
        "junction.security.api-key.preconfigured[0].name=Integration Test Key",
        "junction.security.api-key.preconfigured[0].tier=ENTERPRISE",
        "junction.security.ip-rate-limit.enabled=false",
        "junction.security.ip-whitelist.enabled=true",
        "junction.security.ip-whitelist.allow-private-ips=false",
        "junction.security.ip-whitelist.allowed-ips=203.0.113.10,198.51.100.0/24"
    }
)
class GatewayIpWhitelistIntegrationTest {

    private static final String VALID_API_KEY = "junc_abcdefghijklmnopqrstuvwxyz123456";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @PostConstruct
    void setUpMockMvc() {
        this.mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void acceptsExactWhitelistedIp() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(VALID_API_KEY))
                .header("X-Forwarded-For", "203.0.113.10")
                .content(chatRequest()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("Hello"));
    }

    @Test
    void acceptsCidrWhitelistedIp() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(VALID_API_KEY))
                .header("X-Forwarded-For", "198.51.100.42")
                .content(chatRequest()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("Hello"));
    }

    @Test
    void rejectsIpOutsideWhitelist() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(VALID_API_KEY))
                .header("X-Forwarded-For", "192.0.2.50")
                .content(chatRequest()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.type").value("ip_not_allowed"))
            .andExpect(jsonPath("$.error.message").value(containsString("Access denied from IP: 192.0.2.50")));
    }

    private static String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }

    private static String chatRequest() {
        return """
            {
              "model": "test-model",
              "messages": [{"role": "user", "content": "Hello"}],
              "stream": false
            }
            """;
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
        private HttpServer server;

        @PostConstruct
        void start() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
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

        private void handleChat(HttpExchange exchange) throws IOException {
            byte[] response = """
                {"model":"test-model","message":{"content":"Hello"},"done":true}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }
    }
}
