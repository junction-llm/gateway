package io.junction.samples;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        "junction.observability.admin.cache-write-enabled=true",
        "junction.observability.security.password=test-password",
        "management.endpoint.health.show-details=when_authorized",
        "management.endpoints.web.exposure.include=health,info,metrics,junction,junctioncache"
    }
)
class GatewayObservabilityIntegrationTest {
    private static final String MANAGEMENT_USERNAME = "actuator";
    private static final String MANAGEMENT_PASSWORD = "test-password";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private GatewayIntegrationTest.StubOllamaBackend backend;

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
    void gatewayResponsesStillWorkWithoutBasicAuthAndExposeTraceHeaders() throws Exception {
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
            .andExpect(header().exists("X-Trace-ID"));

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
            .andExpect(header().exists("X-Trace-ID"));

        mockMvc.perform(get("/v1/models").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-ID"));
    }

    @Test
    void healthIsPublicButDetailedOnlyForAuthenticatedOperators() throws Exception {
        mockMvc.perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"status\":\"UP\"")))
            .andExpect(content().string(not(containsString("configuredProviders"))))
            .andExpect(content().string(not(containsString("ollama"))));

        mockMvc.perform(get("/actuator/health")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"status\":\"UP\"")))
            .andExpect(content().string(containsString("configuredProviders")))
            .andExpect(content().string(containsString("ollama")));
    }

    @Test
    void protectedActuatorEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/info").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/metrics/junction.requests").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/junction").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOperatorsCanReadProtectedActuatorEndpoints() throws Exception {
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
            .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.junction.cacheWriteEnabled").value(true));

        mockMvc.perform(get("/actuator/metrics/junction.requests")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("junction.requests"));

        mockMvc.perform(get("/actuator/metrics/junction.provider.requests")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("junction.provider.requests"));

        mockMvc.perform(get("/actuator/junction")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers[0].providerId").value("ollama"))
            .andExpect(jsonPath("$.config.cacheWriteEnabled").value(true))
            .andExpect(jsonPath("$.apiKeys.total").value(0));
    }

    @Test
    void cacheEvictionRequiresAuthenticationAndRemainsOptIn() throws Exception {
        mockMvc.perform(get("/v1/models").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/junction")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modelCache", hasSize(1)))
            .andExpect(jsonPath("$.modelCache[0].providerId").value("ollama"));

        mockMvc.perform(delete("/actuator/junctioncache/ollama").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/actuator/junctioncache/ollama")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("provider"))
            .andExpect(jsonPath("$.providerId").value("ollama"));

        mockMvc.perform(get("/actuator/junction")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modelCache", hasSize(0)));
    }

    private String managementBasicAuth() {
        var credentials = MANAGEMENT_USERNAME + ":" + MANAGEMENT_PASSWORD;
        var encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
