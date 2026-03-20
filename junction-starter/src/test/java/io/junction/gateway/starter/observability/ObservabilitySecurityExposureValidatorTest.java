package io.junction.gateway.starter.observability;

import io.junction.gateway.starter.JunctionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilitySecurityExposureValidatorTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ValidationTestConfiguration.class);

    @Test
    void failsWhenProtectedEndpointsAreExposedWithoutPassword() {
        contextRunner
            .withPropertyValues(
                "junction.observability.security.enabled=true",
                "management.endpoints.web.exposure.include=health,info,metrics"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("junction.observability.security.password");
            });
    }

    @Test
    void allowsBlankPasswordWhenOnlyPublicHealthIsExposed() {
        contextRunner
            .withPropertyValues(
                "junction.observability.security.enabled=true",
                "junction.observability.security.public-health-enabled=true",
                "management.endpoints.web.exposure.include=health"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsWhenHealthIsProtectedAndPasswordIsBlank() {
        contextRunner
            .withPropertyValues(
                "junction.observability.security.enabled=true",
                "junction.observability.security.public-health-enabled=false",
                "management.endpoints.web.exposure.include=health"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("junction.observability.security.password");
            });
    }

    @Test
    void skipsPasswordRequirementWhenBuiltInManagementSecurityIsDisabled() {
        contextRunner
            .withPropertyValues(
                "junction.observability.security.enabled=false",
                "management.endpoints.web.exposure.include=health,info,metrics,junction"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JunctionProperties.class)
    static class ValidationTestConfiguration {

        @Bean
        ObservabilitySecurityExposureValidator observabilitySecurityExposureValidator(JunctionProperties properties,
                                                                                     Environment environment) {
            var validator = new ObservabilitySecurityExposureValidator(properties, environment);
            validator.validate();
            return validator;
        }
    }
}
