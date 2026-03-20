package io.junction.gateway.starter.observability;

import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.tracing.GatewayTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

public class MicrometerGatewayTracing implements GatewayTracing {
    private static final Propagator.Getter<Map<String, String>> MAP_GETTER = Map::get;
    private static final Propagator.Setter<Map<String, String>> MAP_SETTER = Map::put;

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerGatewayTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public ContextSnapshot capture() {
        var currentTraceContext = tracer.currentTraceContext().context();
        if (currentTraceContext == null) {
            return NoSpanContextSnapshot.INSTANCE;
        }
        return new MicrometerContextSnapshot(currentTraceContext);
    }

    @Override
    public ContextSnapshot fromTraceparent(String traceparent) {
        return fromIncomingHeaders(traceparent, null, null);
    }

    @Override
    public ContextSnapshot fromIncomingHeaders(String traceparent, String tracestate, String baggage) {
        var headers = new LinkedHashMap<String, String>();
        putIfPresent(headers, "traceparent", traceparent);
        putIfPresent(headers, "tracestate", tracestate);
        putIfPresent(headers, "baggage", baggage);

        if (headers.isEmpty()) {
            return NoSpanContextSnapshot.INSTANCE;
        }

        return new ExtractedSpanBuilderSnapshot(propagator.extract(headers, MAP_GETTER));
    }

    @Override
    public TraceScope startSpan(String name, ContextSnapshot parent) {
        Span startedSpan;
        if (parent instanceof ExtractedSpanBuilderSnapshot snapshot) {
            startedSpan = snapshot.spanBuilder().name(name).start();
        } else {
            var spanBuilder = tracer.spanBuilder().name(name);
            if (parent instanceof MicrometerContextSnapshot snapshot && snapshot.traceContext() != null) {
                spanBuilder.setParent(snapshot.traceContext());
            }
            startedSpan = spanBuilder.start();
        }

        var spanInScope = tracer.withSpan(startedSpan);
        return new MicrometerTraceScope(startedSpan, spanInScope, propagator);
    }

    @Override
    public RequestContext.DistributedTrace currentTrace() {
        return toDistributedTrace(tracer.currentSpan(), propagator);
    }

    private static RequestContext.DistributedTrace toDistributedTrace(Span span, Propagator propagator) {
        if (span == null || span.context() == null) {
            return RequestContext.DistributedTrace.none();
        }

        var headers = new LinkedHashMap<String, String>();
        propagator.inject(span.context(), headers, MAP_SETTER);
        return new RequestContext.DistributedTrace(
            span.context().traceId(),
            span.context().spanId(),
            headers.get("tracestate"),
            headers.get("baggage")
        );
    }

    private static void putIfPresent(Map<String, String> headers, String key, String value) {
        if (value != null && !value.isBlank()) {
            headers.put(key, value);
        }
    }

    private record MicrometerContextSnapshot(TraceContext traceContext) implements ContextSnapshot {
    }

    private record ExtractedSpanBuilderSnapshot(Span.Builder spanBuilder) implements ContextSnapshot {
    }

    private enum NoSpanContextSnapshot implements ContextSnapshot {
        INSTANCE
    }

    private static final class MicrometerTraceScope implements TraceScope {
        private final Span span;
        private final Tracer.SpanInScope spanInScope;
        private final Propagator propagator;
        private final String previousTraceId;
        private final String previousSpanId;
        private boolean closed;

        private MicrometerTraceScope(Span span, Tracer.SpanInScope spanInScope, Propagator propagator) {
            this.span = span;
            this.spanInScope = spanInScope;
            this.propagator = propagator;
            this.previousTraceId = MDC.get("otelTraceId");
            this.previousSpanId = MDC.get("otelSpanId");
            setMdcValue("otelTraceId", span.context().traceId());
            setMdcValue("otelSpanId", span.context().spanId());
        }

        @Override
        public RequestContext.DistributedTrace trace() {
            return toDistributedTrace(span, propagator);
        }

        @Override
        public Map<String, String> propagationHeaders() {
            var headers = new LinkedHashMap<String, String>();
            propagator.inject(span.context(), headers, MAP_SETTER);
            return Map.copyOf(headers);
        }

        @Override
        public void tag(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                span.tag(key, value);
            }
        }

        @Override
        public void event(String value) {
            if (value != null && !value.isBlank()) {
                span.event(value);
            }
        }

        @Override
        public void error(Throwable error) {
            if (error != null) {
                span.error(error);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            restoreMdcValue("otelTraceId", previousTraceId);
            restoreMdcValue("otelSpanId", previousSpanId);
            spanInScope.close();
            span.end();
        }

        private static void setMdcValue(String key, String value) {
            if (value == null || value.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }

        private static void restoreMdcValue(String key, String value) {
            if (value == null || value.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
