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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        "junction.observability.security.public-health-enabled=false",
        "junction.observability.security.password=test-password",
        "management.endpoint.health.show-details=when_authorized",
        "management.endpoints.web.exposure.include=health"
    }
)
class GatewayObservabilityPrivateHealthIntegrationTest {
    private static final String MANAGEMENT_PASSWORD = "test-password";

    @Autowired
    private WebApplicationContext webApplicationContext;

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
    void healthRequiresAuthenticationWhenPublicHealthIsDisabled() throws Exception {
        mockMvc.perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/health")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("configuredProviders")));
    }

    @Test
    void cacheWriteEndpointIsUnavailableWhenNotEnabledOrExposed() throws Exception {
        mockMvc.perform(delete("/actuator/junctioncache/ollama")
                .header("Authorization", managementBasicAuth())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    private String managementBasicAuth() {
        var credentials = "actuator:" + MANAGEMENT_PASSWORD;
        var encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
