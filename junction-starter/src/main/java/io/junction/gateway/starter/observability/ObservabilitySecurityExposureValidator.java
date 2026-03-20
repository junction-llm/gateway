package io.junction.gateway.starter.observability;

import io.junction.gateway.starter.JunctionProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ObservabilitySecurityExposureValidator {
    private static final String HEALTH_ENDPOINT = "health";

    private final JunctionProperties properties;
    private final Environment environment;

    public ObservabilitySecurityExposureValidator(JunctionProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public void validate() {
        var security = properties.getObservability().getSecurity();
        if (!security.isEnabled()) {
            return;
        }

        if (!requiresPassword()) {
            return;
        }

        if (StringUtils.hasText(security.getPassword())) {
            return;
        }

        throw new IllegalStateException(
            "junction.observability.security.password must be set when protected Actuator endpoints are web-exposed."
        );
    }

    private boolean requiresPassword() {
        var exposed = exposedEndpoints();
        if (exposed.isEmpty()) {
            return false;
        }

        if (exposed.contains("*")) {
            if (properties.getObservability().getSecurity().isPublicHealthEnabled()
                && exposed.size() == 1) {
                return false;
            }
            return true;
        }

        return exposed.stream().anyMatch(this::isProtectedEndpoint);
    }

    private Set<String> exposedEndpoints() {
        var included = parseEndpointList(environment.getProperty(
            "management.endpoints.web.exposure.include",
            HEALTH_ENDPOINT
        ));
        var excluded = parseEndpointList(environment.getProperty(
            "management.endpoints.web.exposure.exclude",
            ""
        ));

        if (included.isEmpty() || excluded.contains("*")) {
            return Set.of();
        }

        if (included.contains("*")) {
            return Set.of("*");
        }

        included.removeAll(excluded);
        return included;
    }

    private boolean isProtectedEndpoint(String endpointId) {
        if ("*".equals(endpointId)) {
            return true;
        }

        if (HEALTH_ENDPOINT.equals(endpointId)) {
            return !properties.getObservability().getSecurity().isPublicHealthEnabled();
        }

        return true;
    }

    private Set<String> parseEndpointList(String value) {
        if (!StringUtils.hasText(value)) {
            return new LinkedHashSet<>();
        }

        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(token -> token.toLowerCase(Locale.ROOT))
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }
}
