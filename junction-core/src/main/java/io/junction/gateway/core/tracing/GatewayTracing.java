package io.junction.gateway.core.tracing;

import io.junction.gateway.core.context.RequestContext;

import java.util.Map;

public interface GatewayTracing {
    ContextSnapshot capture();

    ContextSnapshot fromTraceparent(String traceparent);

    default ContextSnapshot fromIncomingHeaders(String traceparent, String tracestate, String baggage) {
        return fromTraceparent(traceparent);
    }

    default TraceScope startSpan(String name) {
        return startSpan(name, capture());
    }

    TraceScope startSpan(String name, ContextSnapshot parent);

    RequestContext.DistributedTrace currentTrace();

    interface ContextSnapshot {
    }

    interface TraceScope extends AutoCloseable {
        RequestContext.DistributedTrace trace();

        Map<String, String> propagationHeaders();

        void tag(String key, String value);

        void event(String value);

        void error(Throwable error);

        @Override
        void close();
    }

    static GatewayTracing noop() {
        return NoopGatewayTracing.INSTANCE;
    }

    final class NoopGatewayTracing implements GatewayTracing {
        private static final NoopGatewayTracing INSTANCE = new NoopGatewayTracing();
        private static final ContextSnapshot SNAPSHOT = new ContextSnapshot() { };
        private static final TraceScope SCOPE = new NoopTraceScope();

        private NoopGatewayTracing() {
        }

        @Override
        public ContextSnapshot capture() {
            return SNAPSHOT;
        }

        @Override
        public ContextSnapshot fromTraceparent(String traceparent) {
            return SNAPSHOT;
        }

        @Override
        public TraceScope startSpan(String name, ContextSnapshot parent) {
            return SCOPE;
        }

        @Override
        public RequestContext.DistributedTrace currentTrace() {
            return RequestContext.DistributedTrace.none();
        }
    }

    final class NoopTraceScope implements TraceScope {
        @Override
        public RequestContext.DistributedTrace trace() {
            return RequestContext.DistributedTrace.none();
        }

        @Override
        public Map<String, String> propagationHeaders() {
            return Map.of();
        }

        @Override
        public void tag(String key, String value) {
        }

        @Override
        public void event(String value) {
        }

        @Override
        public void error(Throwable error) {
        }

        @Override
        public void close() {
        }
    }
}
