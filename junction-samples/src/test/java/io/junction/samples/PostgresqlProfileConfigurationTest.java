package io.junction.samples;

import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.InMemoryApiKeyRepository;
import io.junction.gateway.starter.JunctionProperties;
import io.junction.gateway.starter.security.JdbcApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlProfileConfigurationTest {

    @Test
    void defaultSampleConfigurationKeepsMemoryStorage() {
        try (ConfigurableApplicationContext context = startContext(false)) {
            JunctionProperties properties = context.getBean(JunctionProperties.class);

            assertThat(properties.getSecurity().getApiKey().getStorage()).isEqualTo("memory");
            assertThat(context.getBean(ApiKeyRepository.class)).isInstanceOf(InMemoryApiKeyRepository.class);
        }
    }

    @Test
    void postgresqlProfileSwitchesSampleConfigurationToPostgresqlStorage() {
        try (ConfigurableApplicationContext context = startContext(true)) {
            JunctionProperties properties = context.getBean(JunctionProperties.class);

            assertThat(properties.getSecurity().getApiKey().getStorage()).isEqualTo("postgresql");
            assertThat(properties.getSecurity().getApiKey().getPostgresqlUrl())
                .isEqualTo("jdbc:postgresql://localhost:5432/junction");
            assertThat(properties.getSecurity().getApiKey().getPostgresqlUsername()).isEqualTo("junction");
            assertThat(properties.getSecurity().getApiKey().getPostgresqlPassword()).isEqualTo("junction");
            assertThat(context.getBean(ApiKeyRepository.class)).isInstanceOf(JdbcApiKeyRepository.class);
        }
    }

    private ConfigurableApplicationContext startContext(boolean postgresqlProfile) {
        List<String> properties = new ArrayList<>();
        properties.add("server.port=0");
        properties.add("junction.providers.ollama.enabled=false");
        properties.add("junction.providers.gemini.enabled=false");
        properties.add("junction.security.ip-rate-limit.enabled=false");
        properties.add("junction.observability.security.enabled=false");

        if (postgresqlProfile) {
            properties.add("spring.profiles.active=postgresql");
        }

        String[] args = properties.stream()
            .map(property -> "--" + property)
            .toArray(String[]::new);

        return new SpringApplicationBuilder(
            Application.class,
            GatewayAuthenticationIntegrationTest.TestConfig.class,
            TestDataSourceConfiguration.class
        ).run(args);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {
        @Bean(name = "junctionApiKeyPostgresqlDataSource")
        DataSource junctionApiKeyPostgresqlDataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:sample-postgres-profile-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}
