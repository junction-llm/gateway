package io.junction.gateway.core.context;

import java.time.Instant;
import java.util.UUID;

public final class RequestContext {
    private static final ScopedValue<Context> CONTEXT = ScopedValue.newInstance();
    
    public record Context(UUID traceId,
                          String apiKey,
                          String model,
                          Instant startTime,
                          DistributedTrace distributedTrace) {
        public Context(UUID traceId, String apiKey, String model, Instant startTime) {
            this(traceId, apiKey, model, startTime, DistributedTrace.none());
        }

        public static Context current() {
            return CONTEXT.get();
        }

        public static Context currentOrNull() {
            return CONTEXT.isBound() ? CONTEXT.get() : null;
        }

        public String distributedTraceId() {
            return distributedTrace != null ? distributedTrace.traceId() : null;
        }

        public String distributedSpanId() {
            return distributedTrace != null ? distributedTrace.spanId() : null;
        }
    }

    public record DistributedTrace(String traceId, String spanId, String tracestate, String baggage) {
        private static final DistributedTrace NONE = new DistributedTrace(null, null, null, null);

        public DistributedTrace(String traceId, String spanId) {
            this(traceId, spanId, null, null);
        }

        public static DistributedTrace none() {
            return NONE;
        }

        public boolean isPresent() {
            return traceId != null && !traceId.isBlank() && spanId != null && !spanId.isBlank();
        }
    }
    
    public static ScopedValue<Context> key() {
        return CONTEXT;
    }
}
