package io.agent.helm.observability.opentelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.function.Function;

/**
 * Factory for {@link OpenTelemetryRuntimeObserver}. Provides convenient {@code create} overloads for applications that
 * want a self-contained SDK wired with a {@link MetricReader} and {@link SpanExporter} (e.g. for testing or for simple
 * single-process deployments), plus an overload that accepts a pre-built {@link OpenTelemetry} instance so Spring Boot
 * / application-managed OTel setups can reuse the global SDK.
 *
 * <p>This factory never registers a global {@link OpenTelemetry} instance; callers that want
 * {@link GlobalOpenTelemetry} set should do so themselves. The observer works against an explicit {@link OpenTelemetry}
 * reference.
 */
public final class MetricsModule {

    private MetricsModule() {}

    /**
     * Creates an observer bound to a self-contained SDK built from the supplied meter and tracer providers. Useful for
     * tests and single-process applications that own their OTel lifecycle.
     */
    public static OpenTelemetryRuntimeObserver create(
            SdkMeterProvider meterProvider,
            SdkTracerProvider tracerProvider,
            Function<String, String> agentResolver,
            ContentCaptureLevel captureLevel) {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .build();
        return new OpenTelemetryRuntimeObserver(sdk, agentResolver, captureLevel);
    }

    /** Creates an observer bound to a pre-built {@link OpenTelemetry} instance with default settings. */
    public static OpenTelemetryRuntimeObserver create(OpenTelemetry openTelemetry) {
        return new OpenTelemetryRuntimeObserver(openTelemetry);
    }

    /**
     * Creates an observer bound to a pre-built {@link OpenTelemetry} instance with a custom agent resolver and capture
     * level.
     */
    public static OpenTelemetryRuntimeObserver create(
            OpenTelemetry openTelemetry, Function<String, String> agentResolver, ContentCaptureLevel captureLevel) {
        return new OpenTelemetryRuntimeObserver(openTelemetry, agentResolver, captureLevel);
    }

    /**
     * Builds a tracer provider with the given exporter sampling every span. Tests typically pass an
     * {@code InMemorySpanExporter} so finished spans can be asserted on.
     */
    public static SdkTracerProvider tracerProvider(SpanExporter exporter) {
        return SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter))
                .build();
    }

    /** Builds a meter provider with a single metric reader (e.g. {@code InMemoryMetricReader}). */
    public static SdkMeterProvider meterProvider(MetricReader reader) {
        return SdkMeterProvider.builder().registerMetricReader(reader).build();
    }
}
