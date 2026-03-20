package io.junction.gateway.starter.observability;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JunctionOpenTelemetryDefaultsEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {
    private static final String PROPERTY_SOURCE_NAME = "junctionOpenTelemetryDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        putIfMissing(environment, defaults, "management.tracing.export.otlp.enabled", false);
        putIfMissing(environment, defaults, "management.logging.export.otlp.enabled", false);

        if (!defaults.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    private void putIfMissing(ConfigurableEnvironment environment,
                              Map<String, Object> defaults,
                              String propertyName,
                              Object value) {
        if (environment.getProperty(propertyName) == null) {
            defaults.put(propertyName, value);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
