package io.junction.gateway.core.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RequestContextTest {

    @Test
    void preservesGatewayAndDistributedTraceMetadata() {
        var gatewayTraceId = UUID.randomUUID();
        var distributedTrace = new RequestContext.DistributedTrace("abc123", "def456");

        var context = new RequestContext.Context(
            gatewayTraceId,
            "api-key",
            "test-model",
            Instant.now(),
            distributedTrace
        );

        assertEquals(gatewayTraceId, context.traceId());
        assertEquals("abc123", context.distributedTraceId());
        assertEquals("def456", context.distributedSpanId());
    }

    @Test
    void defaultContextUsesEmptyDistributedTrace() {
        var context = new RequestContext.Context(UUID.randomUUID(), "api-key", "test-model", Instant.now());

        assertFalse(context.distributedTrace().isPresent());
    }
}
