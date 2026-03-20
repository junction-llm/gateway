package io.junction.gateway.core.telemetry;

public interface GatewayTelemetry {
    GatewayTelemetry NOOP = new GatewayTelemetry() {
    };

    static GatewayTelemetry noop() {
        return NOOP;
    }

    default void recordRouteSelection(String operation, String providerId, boolean preferred) {
    }

    default void recordProviderHealthCheck(String providerId, boolean healthy, long durationNanos) {
    }

    default void recordProviderRequest(String providerId, String operation, String outcome, long durationNanos) {
    }

    default void recordModelCacheHit(String providerId) {
    }

    default void recordModelCacheMiss(String providerId) {
    }

    default void recordModelCacheEviction(String providerId) {
    }
}
