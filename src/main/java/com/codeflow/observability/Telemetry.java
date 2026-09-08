package com.codeflow.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Locale;

/**
 * Process-wide OpenTelemetry bootstrap used by the runtime and A2A client.
 *
 * <p>An in-process SDK is enabled by default so W3C trace identifiers exist
 * even when no collector is configured. Set {@code OTEL_EXPORTER_OTLP_ENDPOINT}
 * (or {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}) to export spans over
 * OTLP/HTTP. Set {@code OTEL_SDK_DISABLED=true} for a complete no-op.</p>
 */
public final class Telemetry {
    private static final String INSTRUMENTATION_NAME = "com.codeflow";
    private static final State STATE = initialize();

    private Telemetry() { }

    public static Tracer tracer() {
        return STATE.openTelemetry().getTracer(INSTRUMENTATION_NAME);
    }

    public static boolean enabled() {
        return STATE.enabled();
    }

    /** Injects the current W3C Trace Context into an outbound JDK HTTP request. */
    public static void inject(HttpRequest.Builder builder) {
        STATE.openTelemetry().getPropagators().getTextMapPropagator().inject(
                Context.current(), builder, HTTP_REQUEST_SETTER);
    }

    public static SpanScope startSpan(String name, SpanKind kind) {
        Span span = tracer().spanBuilder(name).setSpanKind(kind).startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    public static String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    private static State initialize() {
        if (truthy(System.getenv("OTEL_SDK_DISABLED"))) {
            return new State(OpenTelemetry.noop(), false, null);
        }

        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), env("OTEL_SERVICE_NAME", "codeflow-java"),
                AttributeKey.stringKey("service.version"), "1.0.0")));
        SdkTracerProviderBuilder provider = SdkTracerProvider.builder().setResource(resource);

        String endpoint = firstNonBlank(
                System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
                System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));
        if (endpoint != null) {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(traceEndpoint(endpoint))
                    .setTimeout(Duration.ofSeconds(10))
                    .build();
            provider.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }

        SdkTracerProvider tracerProvider = provider.build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        java.lang.Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().name("codeflow-otel-shutdown").unstarted(tracerProvider::close));
        return new State(sdk, true, tracerProvider);
    }

    private static String traceEndpoint(String configured) {
        String endpoint = configured.strip();
        String tracesEndpoint = System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        if (tracesEndpoint != null && !tracesEndpoint.isBlank()) return endpoint;
        if (endpoint.endsWith("/v1/traces")) return endpoint;
        return endpoint.replaceFirst("/+$", "") + "/v1/traces";
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean truthy(String value) {
        return value != null && switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
    }

    private static final TextMapSetter<HttpRequest.Builder> HTTP_REQUEST_SETTER =
            (carrier, key, value) -> carrier.header(key, value);

    private record State(OpenTelemetry openTelemetry, boolean enabled,
                         SdkTracerProvider tracerProvider) { }

    /** A current span whose scope and lifecycle are closed together. */
    public static final class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public Span span() {
            return span;
        }

        public void fail(Throwable failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getMessage() == null ? "error" : failure.getMessage());
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
