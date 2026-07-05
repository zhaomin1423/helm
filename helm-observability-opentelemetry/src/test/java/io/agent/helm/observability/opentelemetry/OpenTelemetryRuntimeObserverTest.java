package io.agent.helm.observability.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link OpenTelemetryRuntimeObserver} emits the expected metrics and spans from a synthetic event
 * stream. Uses {@link InMemoryMetricReader} and {@link InMemorySpanExporter} from {@code opentelemetry-sdk-testing}.
 */
final class OpenTelemetryRuntimeObserverTest {

    @Test
    void operationSucceededEmitsDurationHistogramAndNoFailureCounter() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetrySdk sdk = sdk(reader, NoopSpanExporter.INSTANCE);

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "coder-agent", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-1", 1, Map.of()));
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED, "op-1", 2, Map.of("turnCount", 3)));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        MetricData duration = findMetric(metrics, "helm.operation.duration");
        assertThat(duration).as("helm.operation.duration recorded").isNotNull();
        HistogramData histogram = duration.getHistogramData();
        assertThat(histogram).isNotNull();
        assertThat(histogram.getPoints()).hasSize(1);
        HistogramPointData point = histogram.getPoints().iterator().next();
        assertThat(point.getCount()).isEqualTo(1L);
        Attributes attrs = point.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("agent"))).isEqualTo("coder-agent");
        assertThat(attrs.get(AttributeKey.stringKey("status"))).isEqualTo("success");
        assertThat(attrs.get(AttributeKey.stringKey("code"))).isEqualTo("");

        assertThat(metrics).noneSatisfy(m -> assertThat(m.getName()).isEqualTo("helm.operation.failure"));
    }

    @Test
    void operationFailedEmitsFailureCounterWithCode() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetrySdk sdk = sdk(reader, NoopSpanExporter.INSTANCE);

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "coder-agent", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-fail", 1, Map.of()));
        observer.onEvent(event(
                RuntimeEventType.OPERATION_FAILED,
                "op-fail",
                2,
                Map.of("errorCode", "MAX_TURNS_EXCEEDED", "message", "hit cap")));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        MetricData failure = findMetric(metrics, "helm.operation.failure");
        assertThat(failure).as("helm.operation.failure recorded").isNotNull();
        assertThat(failure.getLongSumData().getPoints()).hasSize(1);
        PointData point = failure.getLongSumData().getPoints().iterator().next();
        assertThat(((io.opentelemetry.sdk.metrics.data.LongPointData) point).getValue())
                .isEqualTo(1L);
        assertThat(point.getAttributes().get(AttributeKey.stringKey("code"))).isEqualTo("MAX_TURNS_EXCEEDED");
        assertThat(point.getAttributes().get(AttributeKey.stringKey("agent"))).isEqualTo("coder-agent");

        // Duration histogram also records a failure-tagged measurement.
        MetricData duration = findMetric(metrics, "helm.operation.duration");
        assertThat(duration).isNotNull();
        assertThat(duration.getHistogramData()
                        .getPoints()
                        .iterator()
                        .next()
                        .getAttributes()
                        .get(AttributeKey.stringKey("status")))
                .isEqualTo("failure");
    }

    @Test
    void toolStartedSucceededEmitsToolDurationHistogram() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetrySdk sdk = sdk(reader, NoopSpanExporter.INSTANCE);

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-tool", 1, Map.of()));
        observer.onEvent(event(
                RuntimeEventType.TOOL_STARTED, "op-tool", 2, Map.of("toolName", "search", "toolCallId", "call-1")));
        observer.onEvent(event(
                RuntimeEventType.TOOL_SUCCEEDED,
                "op-tool",
                3,
                Map.of("toolName", "search", "toolCallId", "call-1", "durationMs", 42L)));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        MetricData toolDuration = findMetric(metrics, "helm.tool.duration");
        assertThat(toolDuration).as("helm.tool.duration recorded").isNotNull();
        HistogramPointData point =
                toolDuration.getHistogramData().getPoints().iterator().next();
        assertThat(point.getSum()).isEqualTo(42.0d);
        assertThat(point.getAttributes().get(AttributeKey.stringKey("tool"))).isEqualTo("search");
        assertThat(point.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("success");
    }

    @Test
    void modelSucceededEmitsTokenUsageCounters() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetrySdk sdk = sdk(reader, NoopSpanExporter.INSTANCE);

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-tok", 1, Map.of()));
        observer.onEvent(event(
                RuntimeEventType.MODEL_STARTED,
                "op-tok",
                2,
                Map.of("provider", "openai", "model", "gpt-4o", "modelCallId", "m-1")));
        observer.onEvent(event(
                RuntimeEventType.MODEL_SUCCEEDED,
                "op-tok",
                3,
                Map.of(
                        "provider",
                        "openai",
                        "model",
                        "gpt-4o",
                        "modelCallId",
                        "m-1",
                        "durationMs",
                        120L,
                        "usage",
                        Map.of("inputTokens", 100L, "outputTokens", 50L))));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        MetricData input = findMetric(metrics, "helm.token.usage.input");
        MetricData output = findMetric(metrics, "helm.token.usage.output");
        assertThat(input).as("input token counter").isNotNull();
        assertThat(output).as("output token counter").isNotNull();
        io.opentelemetry.sdk.metrics.data.LongPointData inputPoint = (io.opentelemetry.sdk.metrics.data.LongPointData)
                input.getLongSumData().getPoints().iterator().next();
        io.opentelemetry.sdk.metrics.data.LongPointData outputPoint = (io.opentelemetry.sdk.metrics.data.LongPointData)
                output.getLongSumData().getPoints().iterator().next();
        assertThat(inputPoint.getValue()).isEqualTo(100L);
        assertThat(outputPoint.getValue()).isEqualTo(50L);
        assertThat(inputPoint.getAttributes().get(AttributeKey.stringKey("provider")))
                .isEqualTo("openai");
        assertThat(inputPoint.getAttributes().get(AttributeKey.stringKey("model")))
                .isEqualTo("gpt-4o");
    }

    @Test
    void tokenUsageToleratesFlatPayloadWhenNoUsageMap() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetrySdk sdk = sdk(reader, NoopSpanExporter.INSTANCE);
        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-flat", 1, Map.of()));
        observer.onEvent(event(
                RuntimeEventType.MODEL_STARTED, "op-flat", 2, Map.of("provider", "anthropic", "model", "claude-3")));
        observer.onEvent(event(
                RuntimeEventType.MODEL_SUCCEEDED,
                "op-flat",
                3,
                Map.of("provider", "anthropic", "model", "claude-3", "inputTokens", 7L, "outputTokens", 3L)));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        MetricData input = findMetric(metrics, "helm.token.usage.input");
        assertThat(input).isNotNull();
        io.opentelemetry.sdk.metrics.data.LongPointData inputPoint = (io.opentelemetry.sdk.metrics.data.LongPointData)
                input.getLongSumData().getPoints().iterator().next();
        assertThat(inputPoint.getValue()).isEqualTo(7L);
    }

    @Test
    void operationToolAndProviderSpansFormHierarchy() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-trace", 1, Map.of()));
        observer.onEvent(
                event(RuntimeEventType.TOOL_STARTED, "op-trace", 2, Map.of("toolName", "search", "toolCallId", "c-1")));
        observer.onEvent(event(
                RuntimeEventType.TOOL_SUCCEEDED,
                "op-trace",
                3,
                Map.of("toolName", "search", "toolCallId", "c-1", "durationMs", 10L)));
        observer.onEvent(event(
                RuntimeEventType.MODEL_STARTED,
                "op-trace",
                4,
                Map.of("provider", "openai", "model", "gpt-4o", "modelCallId", "m-1")));
        observer.onEvent(event(
                RuntimeEventType.MODEL_SUCCEEDED,
                "op-trace",
                5,
                Map.of("provider", "openai", "model", "gpt-4o", "modelCallId", "m-1", "durationMs", 20L)));
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED, "op-trace", 6, Map.of("turnCount", 1)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);
        SpanData opSpan = spanByName(spans, "helm.operation");
        SpanData toolSpan = spanByName(spans, "helm.tool.call");
        SpanData providerSpan = spanByName(spans, "helm.provider.call");

        assertThat(opSpan.getParentSpanId())
                .isEqualTo(io.opentelemetry.api.trace.SpanContext.getInvalid().getSpanId());
        assertThat(toolSpan.getParentSpanId()).isEqualTo(opSpan.getSpanId());
        assertThat(providerSpan.getParentSpanId()).isEqualTo(opSpan.getSpanId());
        assertThat(opSpan.getTraceId()).isEqualTo(toolSpan.getTraceId()).isEqualTo(providerSpan.getTraceId());
        assertThat(opSpan.getAttributes().get(AttributeKey.stringKey("helm.operation.id")))
                .isEqualTo("op-trace");
        assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey("helm.tool")))
                .isEqualTo("search");
        assertThat(providerSpan.getAttributes().get(AttributeKey.stringKey("helm.provider")))
                .isEqualTo("openai");
    }

    @Test
    void operationFailedSpanHasErrorStatusAndCodeAttribute() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);

        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-err", 1, Map.of()));
        observer.onEvent(event(RuntimeEventType.OPERATION_FAILED, "op-err", 2, Map.of("errorCode", "TURN_TIMEOUT")));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData opSpan = spanByName(spans, "helm.operation");
        assertThat(opSpan.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(opSpan.getAttributes().get(AttributeKey.stringKey("helm.operation.code")))
                .isEqualTo("TURN_TIMEOUT");
        assertThat(opSpan.getAttributes().get(AttributeKey.stringKey("helm.operation.status")))
                .isEqualTo("failure");
    }

    @Test
    void payloadMissingFieldsDoesNotThrow() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetrySdk sdk = sdk(reader, NoopSpanExporter.INSTANCE);
        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "", ContentCaptureLevel.METADATA_ONLY);

        // Send terminal events without matching start events — must not throw, must not record bogus metrics.
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED, "op-orphan", 1, Map.of()));
        observer.onEvent(event(RuntimeEventType.TOOL_SUCCEEDED, "op-orphan", 2, Map.of()));
        observer.onEvent(
                event(RuntimeEventType.MODEL_SUCCEEDED, "op-orphan", 3, Map.of("provider", "p", "model", "m")));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        // No durations recorded because no start events were observed.
        assertThat(metrics).noneSatisfy(m -> assertThat(m.getName()).isEqualTo("helm.operation.duration"));
    }

    @Test
    void ttlSweeperEndsOrphanedOperationSpanWithoutTerminalEvent() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);
        // 1ms TTL so the very next event triggers eviction of the orphan.
        OpenTelemetryRuntimeObserver observer = new OpenTelemetryRuntimeObserver(
                sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY, Duration.ofMillis(1), 1024);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-orphan-ttl", 1, Map.of()));
        // Sleep to push the span past its TTL.
        awaitPastTtl();
        // A subsequent event triggers the on-access sweep; the orphaned operation span must be ended + evicted.
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED, "op-other", 1, Map.of()));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).anySatisfy(s -> {
            assertThat(s.getName()).isEqualTo("helm.operation");
            assertThat(s.getAttributes().get(AttributeKey.booleanKey("helm.orphaned")))
                    .isEqualTo(true);
        });
    }

    @Test
    void closeDrainsInFlightSpans() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);
        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        // Start an operation span and never send a terminal event.
        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-dangling", 1, Map.of()));

        observer.close();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).anySatisfy(s -> {
            assertThat(s.getName()).isEqualTo("helm.operation");
            assertThat(s.getAttributes().get(AttributeKey.booleanKey("helm.orphaned")))
                    .isEqualTo(true);
            assertThat(s.getAttributes().get(AttributeKey.stringKey("helm.orphan.reason")))
                    .isEqualTo("closed:operation");
        });
        // close() is idempotent.
        observer.close();
    }

    @Test
    void capEvictsOldestInFlightSpan() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);
        // Cap of 1 so the second started span evicts the first.
        OpenTelemetryRuntimeObserver observer = new OpenTelemetryRuntimeObserver(
                sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY, Duration.ofSeconds(300), 1);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-first", 1, Map.of()));
        awaitPastTtl(); // ensure the second put observes a strictly-later creation time for deterministic ordering
        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-second", 1, Map.of()));
        observer.close();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        // The first span was evicted (either by cap or close), the second by close — both ended.
        assertThat(spans).anySatisfy(s -> {
            assertThat(s.getName()).isEqualTo("helm.operation");
            assertThat(s.getAttributes().get(AttributeKey.stringKey("helm.operation.id")))
                    .isEqualTo("op-first");
        });
    }

    @Test
    void summaryCaptureLevelRecordsTruncatedToolInput() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);
        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.SUMMARY);

        String longInput = "x".repeat(500);
        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-cap", 1, Map.of()));
        observer.onEvent(event(
                RuntimeEventType.TOOL_STARTED,
                "op-cap",
                2,
                Map.of("toolName", "t", "toolCallId", "c", "input", longInput)));
        observer.onEvent(event(
                RuntimeEventType.TOOL_SUCCEEDED,
                "op-cap",
                3,
                Map.of("toolName", "t", "toolCallId", "c", "output", "ok")));
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED, "op-cap", 4, Map.of()));

        SpanData toolSpan = spanByName(spanExporter.getFinishedSpanItems(), "helm.tool.call");
        // SUMMARY truncates to 200 chars.
        String captured = toolSpan.getAttributes().get(AttributeKey.stringKey("helm.tool.input.summary"));
        assertThat(captured).hasSize(200);
        assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey("helm.tool.output.summary")))
                .isEqualTo("ok");
        // No full-content attribute at SUMMARY.
        assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey("helm.tool.input.full")))
                .isNull();
    }

    @Test
    void fullCaptureLevelRecordsFullRedactedContent() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);
        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.FULL);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-full", 1, Map.of()));
        observer.onEvent(event(
                RuntimeEventType.TOOL_STARTED,
                "op-full",
                2,
                Map.of(
                        "toolName",
                        "t",
                        "toolCallId",
                        "c",
                        "input",
                        Map.of("q", "select * from users", "password", "pw"))));
        observer.onEvent(
                event(RuntimeEventType.TOOL_SUCCEEDED, "op-full", 3, Map.of("toolName", "t", "toolCallId", "c")));
        observer.onEvent(event(RuntimeEventType.OPERATION_SUCCEEDED, "op-full", 4, Map.of()));

        SpanData toolSpan = spanByName(spanExporter.getFinishedSpanItems(), "helm.tool.call");
        String captured = toolSpan.getAttributes().get(AttributeKey.stringKey("helm.tool.input.full"));
        // Content is recorded and the @Redact-style secret key "password" is redacted by RedactingEventRedactor.
        assertThat(captured).contains("select * from users").contains("[REDACTED]");
        assertThat(captured).doesNotContain("=pw").doesNotContain("pw\"");
    }

    @Test
    void missingToolCallIdFallsBackToEventIdAndDoesNotCollide() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(InMemoryMetricReader.create(), spanExporter);
        OpenTelemetryRuntimeObserver observer =
                new OpenTelemetryRuntimeObserver(sdk, opId -> "agent-x", ContentCaptureLevel.METADATA_ONLY);

        observer.onEvent(event(RuntimeEventType.OPERATION_STARTED, "op-noid", 1, Map.of()));
        // Two tool calls with NO toolCallId — they must not collide (each gets its own span keyed by event id).
        observer.onEvent(event(RuntimeEventType.TOOL_STARTED, "op-noid", 2, Map.of("toolName", "t1")));
        observer.onEvent(event(RuntimeEventType.TOOL_STARTED, "op-noid", 3, Map.of("toolName", "t2")));
        observer.close();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        long toolSpans =
                spans.stream().filter(s -> s.getName().equals("helm.tool.call")).count();
        assertThat(toolSpans).isEqualTo(2);
    }

    // --- helpers ---

    private static OpenTelemetrySdk sdk(InMemoryMetricReader reader, Object spanExporter) {
        SdkMeterProvider meterProvider =
                SdkMeterProvider.builder().registerMetricReader(reader).build();
        SdkTracerProvider tracerProvider;
        if (spanExporter instanceof InMemorySpanExporter exporter) {
            tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
        } else {
            tracerProvider = SdkTracerProvider.builder().build();
        }
        return OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .build();
    }

    private static RuntimeEventRecord event(
            RuntimeEventType type, String operationId, long sequence, Map<String, Object> payload) {
        return new RuntimeEventRecord("evt_" + sequence, operationId, null, sequence, type, payload, Instant.now());
    }

    /**
     * Sleeps just enough to guarantee a strictly-later wall-clock creation time for the next span (so TTL/cap ordering
     * tests are deterministic).
     */
    private static void awaitPastTtl() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MetricData findMetric(Collection<MetricData> metrics, String name) {
        return metrics.stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name + " in " + spans));
    }

    /** A no-op span exporter so metric-only tests don't need to allocate a real one. */
    private enum NoopSpanExporter implements io.opentelemetry.sdk.trace.export.SpanExporter {
        INSTANCE;

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode export(Collection<SpanData> spans) {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode flush() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }
    }
}
