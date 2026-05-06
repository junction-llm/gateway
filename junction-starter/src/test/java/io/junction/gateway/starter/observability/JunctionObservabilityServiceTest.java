package io.junction.gateway.starter.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JunctionObservabilityServiceTest {

    @Test
    void recordsSseChunkSendMetrics() {
        var registry = new SimpleMeterRegistry();
        var service = new JunctionObservabilityService(registry);

        service.recordSseChunkSent("Ollama Main", 128, 2_000_000);

        assertThat(registry.get("junction.sse.chunks").tag("provider", "ollama_main").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("junction.sse.bytes").tag("provider", "ollama_main").counter().count())
            .isEqualTo(128.0);
        assertThat(registry.get("junction.sse.send.duration").tag("provider", "ollama_main").timer().count())
            .isEqualTo(1L);
    }

    @Test
    void recordsSseTerminationOutcome() {
        var registry = new SimpleMeterRegistry();
        var service = new JunctionObservabilityService(registry);

        service.recordSseTermination("gemini", "stream_io_error");

        assertThat(registry.get("junction.sse.terminations")
            .tag("provider", "gemini")
            .tag("outcome", "stream_io_error")
            .counter()
            .count()).isEqualTo(1.0);
    }
}
