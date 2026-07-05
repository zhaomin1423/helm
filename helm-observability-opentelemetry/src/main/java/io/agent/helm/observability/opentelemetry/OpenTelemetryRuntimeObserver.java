package io.agent.helm.observability.opentelemetry;

import io.agent.helm.core.event.RuntimeEventObserver;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link RuntimeEventObserver} that emits OpenTelemetry metrics and traces from the runtime event stream.
 *
 * <p><b>Metrics</b>: {@code helm.operation.duration} (histogram, labels agent/status/code),
 * {@code helm.operation.failure} (counter, label code), {@code helm.tool.duration} (histogram, labels tool/status), and
 * {@code helm.token.usage.input} / {@code helm.token.usage.output} (counters, labels provider/model). Operation and
 * tool durations are computed by pairing STARTED with SUCCEEDED/FAILED events on the same {@code operationId} (and tool
 * call id).
 *
 * <p><b>Tracing</b>: one span per operation ({@code helm.operation}) and child spans per tool call
 * ({@code helm.tool.call}) and provider call ({@code helm.provider.call}). Span parentage is established via explicit
 * OTel {@link Context} so the observer does not depend on thread-local current-span state — events may arrive on
 * different threads once durable scale lands.
 *
 * <p>This observer is a pure consumer. It never re-redacts events (events arrive already redacted by the core runtime)
 * and never mutates the event. Any failure to emit metrics or spans is swallowed and logged at DEBUG so that
 * observability never changes the operation outcome — matching the {@code appendEventSafely} contract.
 *
 * <p>Thread-safety: the OTel SDK is thread-safe. The in-flight span/start-time maps are keyed by {@code operationId}
 * (and tool-call id) and use concurrent maps.
 */
public final class OpenTelemetryRuntimeObserver implements RuntimeEventObserver {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryRuntimeObserver.class);

    private static final String METER_NAME = "io.agent.helm";
    private static final String TRACER_NAME = "io.agent.helm";
    private static final String TRACER_VERSION = "0.1.0";

    private static final String METRIC_OPERATION_DURATION = "helm.operation.duration";
    private static final String METRIC_OPERATION_FAILURE = "helm.operation.failure";
    private static final String METRIC_TOOL_DURATION = "helm.tool.duration";
    private static final String METRIC_TOKEN_INPUT = "helm.token.usage.input";
    private static final String METRIC_TOKEN_OUTPUT = "helm.token.usage.output";

    private final Meter meter;
    private final Tracer tracer;
    private final Function<String, String> agentResolver;
    private final ContentCaptureLevel captureLevel;

    // operationId -> operation Span (in-flight). Removed on operation SUCCEEDED/FAILED.
    private final ConcurrentHashMap<String, Span> operationSpans = new ConcurrentHashMap<>();
    // operationId -> operation start Instant (for duration computation).
    private final ConcurrentHashMap<String, Instant> operationStarts = new ConcurrentHashMap<>();
    // toolSpanKey (operationId + ":" + toolCallId) -> tool Span.
    private final ConcurrentHashMap<String, Span> toolSpans = new ConcurrentHashMap<>();
    // toolSpanKey -> tool start Instant.
    private final ConcurrentHashMap<String, Instant> toolStarts = new ConcurrentHashMap<>();
    // providerSpanKey (operationId + ":" + turnIndex + ":" + modelCallId) -> provider Span.
    private final ConcurrentHashMap<String, Span> providerSpans = new ConcurrentHashMap<>();
    // providerSpanKey -> provider start Instant.
    private final ConcurrentHashMap<String, Instant> providerStarts = new ConcurrentHashMap<>();

    // Metric instruments are built once and reused. Histogram/counter builders are cheap but reusing the built
    // instrument avoids per-event allocation and keeps cardinality controlled by the SDK.
    private final LongHistogram operationDurationHistogram;
    private final LongCounter operationFailureCounter;
    private final LongHistogram toolDurationHistogram;
    private final LongCounter tokenInputCounter;
    private final LongCounter tokenOutputCounter;

    /** Creates an observer with {@link ContentCaptureLevel#METADATA_ONLY} and an empty agent resolver. */
    public OpenTelemetryRuntimeObserver(OpenTelemetry openTelemetry) {
        this(openTelemetry, operationId -> "", ContentCaptureLevel.METADATA_ONLY);
    }

    public OpenTelemetryRuntimeObserver(
            OpenTelemetry openTelemetry, Function<String, String> agentResolver, ContentCaptureLevel captureLevel) {
        this.meter = openTelemetry.getMeter(METER_NAME);
        this.tracer = openTelemetry.getTracer(TRACER_NAME, TRACER_VERSION);
        this.agentResolver = agentResolver;
        this.captureLevel = captureLevel;
        this.operationDurationHistogram = meter.histogramBuilder(METRIC_OPERATION_DURATION)
                .setUnit("ms")
                .ofLongs()
                .build();
        this.operationFailureCounter =
                meter.counterBuilder(METRIC_OPERATION_FAILURE).build();
        this.toolDurationHistogram = meter.histogramBuilder(METRIC_TOOL_DURATION)
                .setUnit("ms")
                .ofLongs()
                .build();
        this.tokenInputCounter = meter.counterBuilder(METRIC_TOKEN_INPUT).build();
        this.tokenOutputCounter = meter.counterBuilder(METRIC_TOKEN_OUTPUT).build();
    }

    @Override
    public void onEvent(RuntimeEventRecord event) {
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            // Observability must never change the operation outcome. Swallow and log at DEBUG.
            if (LOG.isDebugEnabled()) {
                LOG.debug("OpenTelemetry observer failed to process event {}", event.type(), e);
            }
        }
    }

    private void dispatch(RuntimeEventRecord event) {
        String type = event.type();
        if (type == null) {
            return;
        }
        switch (type) {
            case "operation.started" -> startOperationSpan(event);
            case "operation.succeeded" -> endOperationSpan(event, StatusCode.OK, null);
            case "operation.failed" -> endOperationSpan(event, StatusCode.ERROR, errorCode(event));
            case "tool.started" -> startToolSpan(event);
            case "tool.succeeded" -> endToolSpan(event, StatusCode.OK, null);
            case "tool.failed" -> endToolSpan(event, StatusCode.ERROR, errorCode(event));
            case "model.started" -> startProviderSpan(event);
            case "model.succeeded" -> {
                endProviderSpan(event, StatusCode.OK, null);
                recordTokenUsage(event);
            }
            case "model.failed" -> endProviderSpan(event, StatusCode.ERROR, errorCode(event));
            default -> {
                // workflow / turn / skill / sandbox / error events: no metric yet, but record a span event on the
                // operation span when one is in flight, so the trace timeline still surfaces them.
                if (captureLevel != ContentCaptureLevel.METADATA_ONLY) {
                    annotateOperation(event);
                }
            }
        }
    }

    // --- Operation span ---

    private void startOperationSpan(RuntimeEventRecord event) {
        String opId = event.operationId();
        Instant start = event.createdAt();
        Span span = tracer.spanBuilder("helm.operation")
                .setAttribute("helm.operation.id", opId == null ? "" : opId)
                .setAttribute("helm.agent", agentResolver.apply(opId == null ? "" : opId))
                .startSpan();
        operationSpans.put(opId, span);
        operationStarts.put(opId, start);
    }

    private void endOperationSpan(RuntimeEventRecord event, StatusCode status, String code) {
        String opId = event.operationId();
        Span span = operationSpans.remove(opId);
        Instant start = operationStarts.remove(opId);
        if (span == null) {
            return;
        }
        long durationMs = start == null
                ? 0L
                : Math.max(0L, Duration.between(start, event.createdAt()).toMillis());
        span.setAttribute("helm.operation.duration_ms", durationMs);
        span.setAttribute("helm.operation.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null && !code.isEmpty()) {
            span.setAttribute("helm.operation.code", code);
        }
        span.setStatus(status);
        span.end();
        recordOperationMetric(durationMs, opId, status, code);
    }

    // --- Tool span (child of operation span) ---

    private void startToolSpan(RuntimeEventRecord event) {
        String opId = event.operationId();
        String toolCallId = str(event.payload(), "toolCallId");
        String toolName = str(event.payload(), "toolName");
        String key = toolSpanKey(opId, toolCallId);
        Span parent = operationSpans.get(opId);
        Context parentContext =
                parent == null ? Context.current() : Context.current().with(parent);
        Span span = tracer.spanBuilder("helm.tool.call")
                .setParent(parentContext)
                .setAttribute("helm.tool", toolName)
                .setAttribute("helm.tool.call_id", toolCallId == null ? "" : toolCallId)
                .startSpan();
        toolSpans.put(key, span);
        toolStarts.put(key, event.createdAt());
    }

    private void endToolSpan(RuntimeEventRecord event, StatusCode status, String code) {
        String opId = event.operationId();
        String toolCallId = str(event.payload(), "toolCallId");
        String toolName = str(event.payload(), "toolName");
        String key = toolSpanKey(opId, toolCallId);
        Span span = toolSpans.remove(key);
        Instant start = toolStarts.remove(key);
        if (span == null) {
            return;
        }
        long durationMs = event.payload().containsKey("durationMs")
                ? longOf(event.payload().get("durationMs"))
                : start == null
                        ? 0L
                        : Math.max(
                                0L, Duration.between(start, event.createdAt()).toMillis());
        span.setAttribute("helm.tool.duration_ms", durationMs);
        span.setAttribute("helm.tool.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null && !code.isEmpty()) {
            span.setAttribute("helm.tool.code", code);
        }
        span.setStatus(status);
        span.end();
        toolDurationHistogram.record(
                durationMs,
                Attributes.builder()
                        .put("tool", toolName == null ? "" : toolName)
                        .put("status", status == StatusCode.OK ? "success" : "failure")
                        .build());
    }

    // --- Provider/model span (child of operation span) ---

    private void startProviderSpan(RuntimeEventRecord event) {
        String opId = event.operationId();
        String provider = str(event.payload(), "provider");
        String model = str(event.payload(), "model");
        String modelCallId = str(event.payload(), "modelCallId");
        String key = providerSpanKey(opId, modelCallId);
        Span parent = operationSpans.get(opId);
        Context parentContext =
                parent == null ? Context.current() : Context.current().with(parent);
        Span span = tracer.spanBuilder("helm.provider.call")
                .setParent(parentContext)
                .setAttribute("helm.provider", provider == null ? "" : provider)
                .setAttribute("helm.model", model == null ? "" : model)
                .startSpan();
        providerSpans.put(key, span);
        providerStarts.put(key, event.createdAt());
    }

    private void endProviderSpan(RuntimeEventRecord event, StatusCode status, String code) {
        String opId = event.operationId();
        String modelCallId = str(event.payload(), "modelCallId");
        String key = providerSpanKey(opId, modelCallId);
        Span span = providerSpans.remove(key);
        Instant start = providerStarts.remove(key);
        if (span == null) {
            return;
        }
        long durationMs = event.payload().containsKey("durationMs")
                ? longOf(event.payload().get("durationMs"))
                : start == null
                        ? 0L
                        : Math.max(
                                0L, Duration.between(start, event.createdAt()).toMillis());
        span.setAttribute("helm.provider.duration_ms", durationMs);
        span.setAttribute("helm.provider.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null && !code.isEmpty()) {
            span.setAttribute("helm.provider.code", code);
        }
        span.setStatus(status);
        span.end();
    }

    // --- Metrics ---

    private void recordOperationMetric(long durationMs, String operationId, StatusCode status, String code) {
        String agent = agentResolver.apply(operationId == null ? "" : operationId);
        AttributesBuilder builder =
                Attributes.builder().put("agent", agent).put("status", status == StatusCode.OK ? "success" : "failure");
        builder.put("code", code == null ? "" : code);
        operationDurationHistogram.record(durationMs, builder.build());
        if (status == StatusCode.ERROR && code != null && !code.isEmpty()) {
            operationFailureCounter.add(
                    1,
                    Attributes.builder().put("code", code).put("agent", agent).build());
        }
    }

    private void recordTokenUsage(RuntimeEventRecord event) {
        Map<String, Object> payload = event.payload();
        Object usage = payload.get("usage");
        Map<?, ?> usageMap = usage instanceof Map<?, ?> m ? m : null;
        // Token usage may also be flattened into top-level payload (inputTokens/outputTokens), as noted in the
        // design doc payload schema. Fall back to top-level keys to tolerate either shape.
        long inputTokens = usageMap != null ? longOf(usageMap.get("inputTokens")) : longOf(payload.get("inputTokens"));
        long outputTokens =
                usageMap != null ? longOf(usageMap.get("outputTokens")) : longOf(payload.get("outputTokens"));
        if (inputTokens == 0L && outputTokens == 0L) {
            return;
        }
        String provider = str(payload, "provider");
        String model = str(payload, "model");
        Attributes attrs = Attributes.builder()
                .put("provider", provider == null ? "unknown" : provider)
                .put("model", model == null ? "unknown" : model)
                .build();
        tokenInputCounter.add(inputTokens, attrs);
        tokenOutputCounter.add(outputTokens, attrs);
    }

    // --- Span event annotation for non-lifecycle events when capture level allows ---

    private void annotateOperation(RuntimeEventRecord event) {
        Span span = operationSpans.get(event.operationId());
        if (span == null) {
            return;
        }
        span.setAttribute("helm.event." + event.type(), event.sequence());
    }

    // --- Helpers ---

    private static String toolSpanKey(String opId, String toolCallId) {
        return (opId == null ? "" : opId) + ":" + (toolCallId == null ? "" : toolCallId);
    }

    private static String providerSpanKey(String opId, String modelCallId) {
        return (opId == null ? "" : opId) + ":model:" + (modelCallId == null ? "" : modelCallId);
    }

    private static String str(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String errorCode(RuntimeEventRecord event) {
        Object code = event.payload().get("errorCode");
        return code == null ? "" : String.valueOf(code);
    }

    private static long longOf(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
