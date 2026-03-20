package io.junction.samples;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(
    classes = {Application.class, GatewayIntegrationTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "junction.providers.ollama.enabled=true",
        "junction.providers.gemini.enabled=false",
        "junction.security.api-key.required=false",
        "junction.security.ip-rate-limit.enabled=false",
        "junction.observability.security.password=test-password",
        "management.endpoint.health.show-details=when_authorized",
        "management.endpoints.web.exposure.include=health,metrics,prometheus,junction",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.propagation.type=w3c",
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false"
    }
)
class GatewayTracingIntegrationTest {
    private static final String MANAGEMENT_PASSWORD = "test-password";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private GatewayIntegrationTest.StubOllamaBackend backend;

    @Autowired
    private InMemorySpanExporter spanExporter;

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

    @BeforeEach
    void resetState() {
        spanExporter.reset();
        backend.setChatStatus(200);
        backend.setEmbeddingStatus(200);
        backend.setChatResponse("""
            {"model":"test-model","message":{"content":"Hello"},"done":false}
            {"model":"test-model","message":{"content":" world"},"done":true}
            """);
        backend.setEmbeddingResponse("""
            {"model":"embeddinggemma","embeddings":[[0.1,0.2,0.3]],"prompt_eval_count":12}
            """);
    }

    @Test
    void continuesIncomingTraceAndPropagatesW3cHeadersToProvider() throws Exception {
        var incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        var incomingParentSpanId = "00f067aa0ba902b7";

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("traceparent", "00-" + incomingTraceId + "-" + incomingParentSpanId + "-01")
                .header("tracestate", "vendor=1")
                .content("""
                    {
                      "model": "test-model",
                      "messages": [{"role": "user", "content": "Hello"}],
                      "stream": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-ID"));

        var providerTraceparent = backend.lastChatTraceparent();
        assertNotNull(providerTraceparent, () -> "No traceparent was propagated. Spans: " + describeFinishedSpans());
        assertTrue(
            providerTraceparent.startsWith("00-" + incomingTraceId + "-"),
            () -> "Unexpected provider traceparent: " + providerTraceparent
        );
        assertFalse(
            providerTraceparent.contains("-" + incomingParentSpanId + "-01"),
            () -> "Provider traceparent reused the incoming parent span id: " + providerTraceparent
        );
        assertEquals("vendor=1", backend.lastChatTracestate());

        var spans = awaitSpans(span -> incomingTraceId.equals(span.getTraceId())
            && Set.of("junction.router.select", "junction.provider.chat").contains(span.getName()), 2);

        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("junction.router.select")));
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("junction.provider.chat")));
    }

    @Test
    void modelsEndpointEmitsCacheRefreshAndProviderModelSpans() throws Exception {
        mockMvc.perform(get("/v1/models").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-ID"));

        var spans = awaitSpans(span -> Set.of("junction.model_cache.refresh", "junction.provider.models").contains(span.getName()), 2);

        assertTrue(
            spans.stream().anyMatch(span -> span.getName().equals("junction.model_cache.refresh")),
            () -> "Finished spans: " + describeFinishedSpans()
        );
        assertTrue(
            spans.stream().anyMatch(span -> span.getName().equals("junction.provider.models")),
            () -> "Finished spans: " + describeFinishedSpans()
        );
    }

    @Test
    void prometheusEndpointRequiresAuthenticationAndExposesJunctionMetrics() throws Exception {
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
            .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("junction_requests_seconds_count")));
    }

    @Test
    void providerFailuresProduceErrorSpans() throws Exception {
        backend.setEmbeddingStatus(500);
        backend.setEmbeddingResponse("{\"error\":\"boom\"}");

        mockMvc.perform(post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "embeddinggemma",
                      "input": "Hello"
                    }
                    """))
            .andExpect(status().isBadGateway());

        var spans = awaitSpans(span -> "junction.provider.embeddings".equals(span.getName()), 1);
        var providerSpan = spans.stream()
            .filter(span -> "junction.provider.embeddings".equals(span.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Finished spans: " + describeFinishedSpans()));

        assertEquals(StatusCode.ERROR, providerSpan.getStatus().getStatusCode());
    }

    private List<SpanData> awaitSpans(java.util.function.Predicate<SpanData> predicate, int minimumMatches) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<SpanData> matches = List.of();
        while (System.nanoTime() < deadline) {
            matches = spanExporter.getFinishedSpanItems().stream()
                .filter(predicate)
                .toList();
            if (matches.size() >= minimumMatches) {
                return matches;
            }
            Thread.sleep(50);
        }
        return matches;
    }

    private String managementBasicAuth() {
        var credentials = "actuator:" + MANAGEMENT_PASSWORD;
        var encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String describeFinishedSpans() {
        return spanExporter.getFinishedSpanItems().stream()
            .map(span -> span.getName() + "[" + span.getTraceId() + "/" + span.getSpanId() + "]")
            .toList()
            .toString();
    }
}
