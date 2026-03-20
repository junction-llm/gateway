package io.junction.gateway.starter.observability;

import java.util.Locale;

final class JunctionMetrics {
    private JunctionMetrics() {
    }

    static String tagValue(String value, String fallback) {
        if (value == null) {
            return fallback;
        }

        var normalized = value.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]", "_");

        return normalized.isBlank() ? fallback : normalized;
    }
}
