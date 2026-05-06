package io.junction.gateway.starter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JunctionObservabilityService {
    private final MeterRegistry meterRegistry;

    public JunctionObservabilityService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public RequestTracker startRequest(String endpoint, String responseMode) {
        return new RequestTracker(meterRegistry, endpoint, responseMode);
    }

    public void recordAuthFailure(String reason) {
        Counter.builder("junction.auth.failures")
            .description("Authentication and authorization failures")
            .tag("reason", JunctionMetrics.tagValue(reason, "unknown"))
            .register(meterRegistry)
            .increment();
    }

    public void recordSseChunkSent(String providerId, long bytes, long durationNanos) {
        var provider = JunctionMetrics.tagValue(providerId, "gateway");
        Counter.builder("junction.sse.chunks")
            .description("SSE response chunks sent to clients")
            .tag("provider", provider)
            .register(meterRegistry)
            .increment();

        Timer.builder("junction.sse.send.duration")
            .description("Duration spent serializing and sending SSE chunks to clients")
            .tag("provider", provider)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS);

        if (bytes >= 0) {
            Counter.builder("junction.sse.bytes")
                .description("Approximate SSE payload bytes sent to clients")
                .tag("provider", provider)
                .baseUnit("bytes")
                .register(meterRegistry)
                .increment(bytes);
        }
    }

    public void recordSseTermination(String providerId, String outcome) {
        Counter.builder("junction.sse.terminations")
            .description("SSE stream termination outcomes")
            .tag("provider", JunctionMetrics.tagValue(providerId, "gateway"))
            .tag("outcome", JunctionMetrics.tagValue(outcome, "unknown"))
            .register(meterRegistry)
            .increment();
    }

    public static final class RequestTracker {
        private final MeterRegistry meterRegistry;
        private final long startNanos = System.nanoTime();
        private final LongTaskTimer.Sample activeSample;
        private final String endpoint;
        private final String responseMode;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile String provider = "gateway";
        private volatile String clientAdapter = "none";
        private volatile String authSource = "unknown";

        private RequestTracker(MeterRegistry meterRegistry, String endpoint, String responseMode) {
            this.meterRegistry = meterRegistry;
            this.endpoint = JunctionMetrics.tagValue(endpoint, "unknown");
            this.responseMode = JunctionMetrics.tagValue(responseMode, "unknown");
            this.activeSample = LongTaskTimer.builder("junction.requests.active")
                .description("Active gateway requests")
                .tag("endpoint", this.endpoint)
                .tag("response_mode", this.responseMode)
                .register(meterRegistry)
                .start();
        }

        public void provider(String providerId) {
            this.provider = JunctionMetrics.tagValue(providerId, "gateway");
        }

        public void clientAdapter(String adapterId) {
            this.clientAdapter = JunctionMetrics.tagValue(adapterId, "none");
        }

        public void authSource(String authSource) {
            this.authSource = JunctionMetrics.tagValue(authSource, "unknown");
        }

        public boolean isFinished() {
            return finished.get();
        }

        public void finishSuccess() {
            finish("success");
        }

        public void finishFailure(String outcome) {
            finish(outcome);
        }

        private void finish(String outcome) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }

            Timer.builder("junction.requests")
                .description("Gateway request duration")
                .tags(Tags.of(
                    "endpoint", endpoint,
                    "response_mode", responseMode,
                    "provider", provider,
                    "outcome", JunctionMetrics.tagValue(outcome, "unknown"),
                    "auth_source", authSource,
                    "client_adapter", clientAdapter
                ))
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);

            activeSample.stop();
        }
    }
}
