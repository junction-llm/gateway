package io.junction.samples;

import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class GatewayPersistentApiKeyStorageIntegrationTest {

    private static final String VALID_API_KEY = "junc_abcdefghijklmnopqrstuvwxyz123456";

    @TempDir
    Path tempDir;

    @Test
    void fileStoragePersistsKeysAcrossRestartAndSkipsDuplicateSeed() throws Exception {
        verifyPersistentStorage(
            "file",
            List.of("junction.security.api-key.file-path=" + tempDir.resolve("api-keys.yml"))
        );
    }

    @Test
    void h2StoragePersistsKeysAcrossRestartAndSkipsDuplicateSeed() throws Exception {
        String h2FilePath = tempDir.resolve("h2/junction").toAbsolutePath().toString().replace("\\", "/");
        verifyPersistentStorage(
            "h2",
            List.of(
                "junction.security.api-key.h2-url=jdbc:h2:file:" + h2FilePath + ";DB_CLOSE_DELAY=-1",
                "junction.security.api-key.h2-username=sa",
                "junction.security.api-key.h2-password="
            )
        );
    }

    private void verifyPersistentStorage(String storage, List<String> storageProperties) throws Exception {
        try (ConfigurableApplicationContext firstBoot = startContext(storage, storageProperties)) {
            ApiKeyRepository repository = firstBoot.getBean(ApiKeyRepository.class);
            assertThat(repository.count()).isEqualTo(1);

            performAuthorizedChat(firstBoot);

            ApiKey storedKey = onlyKey(repository);
            assertThat(storedKey.requestCount()).isEqualTo(1);
        }

        try (ConfigurableApplicationContext secondBoot = startContext(storage, storageProperties)) {
            ApiKeyRepository repository = secondBoot.getBean(ApiKeyRepository.class);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(onlyKey(repository).requestCount()).isEqualTo(1);

            performAuthorizedChat(secondBoot);

            assertThat(onlyKey(repository).requestCount()).isEqualTo(2);
        }
    }

    private ConfigurableApplicationContext startContext(String storage, List<String> storageProperties) {
        List<String> properties = new ArrayList<>();
        properties.add("server.port=0");
        properties.add("junction.providers.ollama.enabled=true");
        properties.add("junction.providers.gemini.enabled=false");
        properties.add("junction.security.api-key.required=true");
        properties.add("junction.security.api-key.storage=" + storage);
        properties.add("JUNCTION_API_KEY_1=" + VALID_API_KEY);
        properties.add("junction.security.ip-rate-limit.enabled=false");
        properties.add("junction.observability.security.enabled=false");
        properties.addAll(storageProperties);

        String[] args = properties.stream()
            .map(property -> "--" + property)
            .toArray(String[]::new);

        return new SpringApplicationBuilder(Application.class, GatewayAuthenticationIntegrationTest.TestConfig.class)
            .run(args);
    }

    private void performAuthorizedChat(ConfigurableApplicationContext context) throws Exception {
        WebApplicationContext webApplicationContext = (WebApplicationContext) context;
        MockMvc mockMvc = webAppContextSetup(webApplicationContext).build();

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(VALID_API_KEY))
                .content(chatRequest(false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("Hello world"));
    }

    private ApiKey onlyKey(ApiKeyRepository repository) {
        assertThat(repository.findAll()).hasSize(1);
        return repository.findAll().getFirst();
    }

    private static String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }

    private static String chatRequest(boolean stream) {
        return """
            {
              "model": "test-model",
              "messages": [{"role": "user", "content": "Hello"}],
              "stream": %s
            }
            """.formatted(stream);
    }
}
