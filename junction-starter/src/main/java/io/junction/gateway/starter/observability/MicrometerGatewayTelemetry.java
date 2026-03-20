package io.junction.gateway.starter.observability;

import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

public class MicrometerGatewayTelemetry implements GatewayTelemetry {
    private final MeterRegistry meterRegistry;

    public MicrometerGatewayTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordRouteSelection(String operation, String providerId, boolean preferred) {
        Counter.builder("junction.router.selections")
            .description("Gateway route selections")
            .tag("operation", JunctionMetrics.tagValue(operation, "unknown"))
            .tag("provider", JunctionMetrics.tagValue(providerId, "unknown"))
            .tag("preferred", Boolean.toString(preferred))
            .register(meterRegistry)
            .increment();
    }

    @Override
    public void recordProviderHealthCheck(String providerId, boolean healthy, long durationNanos) {
        Counter.builder("junction.provider.health.checks")
            .description("Provider health checks")
            .tag("provider", JunctionMetrics.tagValue(providerId, "unknown"))
            .tag("healthy", Boolean.toString(healthy))
            .register(meterRegistry)
            .increment();

        Timer.builder("junction.provider.health.check.duration")
            .description("Provider health check duration")
            .tag("provider", JunctionMetrics.tagValue(providerId, "unknown"))
            .tag("healthy", Boolean.toString(healthy))
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordProviderRequest(String providerId, String operation, String outcome, long durationNanos) {
        var provider = JunctionMetrics.tagValue(providerId, "unknown");
        var normalizedOperation = JunctionMetrics.tagValue(operation, "unknown");
        var normalizedOutcome = JunctionMetrics.tagValue(outcome, "unknown");

        Timer.builder("junction.provider.requests")
            .description("Provider request duration")
            .tag("provider", provider)
            .tag("operation", normalizedOperation)
            .tag("outcome", normalizedOutcome)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS);

        if (!"success".equals(normalizedOutcome)) {
            Counter.builder("junction.provider.errors")
                .description("Provider request failures")
                .tag("provider", provider)
                .tag("operation", normalizedOperation)
                .tag("outcome", normalizedOutcome)
                .register(meterRegistry)
                .increment();
        }
    }

    @Override
    public void recordModelCacheHit(String providerId) {
        Counter.builder("junction.model.cache.hits")
            .description("Model cache hits")
            .tag("provider", JunctionMetrics.tagValue(providerId, "unknown"))
            .register(meterRegistry)
            .increment();
    }

    @Override
    public void recordModelCacheMiss(String providerId) {
        Counter.builder("junction.model.cache.misses")
            .description("Model cache misses")
            .tag("provider", JunctionMetrics.tagValue(providerId, "unknown"))
            .register(meterRegistry)
            .increment();
    }

    @Override
    public void recordModelCacheEviction(String providerId) {
        Counter.builder("junction.model.cache.evictions")
            .description("Model cache evictions")
            .tag("provider", JunctionMetrics.tagValue(providerId, "unknown"))
            .register(meterRegistry)
            .increment();
    }
}
