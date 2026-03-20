package io.junction.gateway.core.tracing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayTracingTest {

    @Test
    void noopTracingProvidesEmptyTraceMetadata() {
        var tracing = GatewayTracing.noop();

        assertFalse(tracing.currentTrace().isPresent());
        assertTrue(tracing.startSpan("test").propagationHeaders().isEmpty());
    }

    @Test
    void noopTracingScopesAreSafeToClose() {
        var tracing = GatewayTracing.noop();
        var scope = tracing.startSpan("test");

        assertDoesNotThrow(scope::close);
    }
}
