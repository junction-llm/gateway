package io.junction.gateway.core.context;

import java.time.Instant;
import java.util.UUID;

public final class RequestContext {
    private static final ScopedValue<Context> CONTEXT = ScopedValue.newInstance();
    
    public record Context(UUID traceId, String apiKey, String model, Instant startTime) {
        public static Context current() {
            return CONTEXT.get();
        }
    }
    
    public static ScopedValue<Context> key() {
        return CONTEXT;
    }
}
