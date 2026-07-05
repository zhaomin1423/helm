package io.agent.helm.observability.opentelemetry;

import io.agent.helm.core.event.RuntimeEventObserver;
import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.event.RuntimeEventType;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p><b>Orphaned-span safety</b>: in-flight spans are tracked in bounded maps (default {@value #DEFAULT_MAX_IN_FLIGHT}
 * entries). A start event without a matching terminal event would leak a span forever; this observer bounds the leak
 * with (a) a TTL sweep run on every {@link #onEvent} call (default {@value #DEFAULT_SPAN_TTL_SECONDS}s) that ends and
 * evicts expired spans, and (b) a hard cap that evicts the oldest entry when the map is full. {@link #close()} drains
 * every in-flight span so shutdown is clean. The observer is {@link AutoCloseable}; callers that own the observer
 * lifecycle SHOULD call {@link #close()} on shutdown.
 *
 * <p><b>Content capture</b>: at {@link ContentCaptureLevel#SUMMARY} the observer records a 200-char truncated
 * (redacted) snapshot of tool input/output and model prompt as span attributes; at {@link ContentCaptureLevel#FULL} the
 * full (redacted) snapshot is recorded. Content never enters metric labels. Redaction is applied defensively via
 * {@link RedactingEventRedactor} even though the core runtime's {@code EventRedactor} already redacts event payloads
 * upstream — the adapter never re-introduces sensitive content.
 *
 * <p>This observer is a pure consumer. It never mutates the event. Any failure to emit metrics or spans is swallowed
 * and logged at DEBUG so that observability never changes the operation outcome — matching the runtime's
 * {@code appendEventSafely} contract.
 *
 * <p>Thread-safety: the OTel SDK is thread-safe. The in-flight span maps are {@link ConcurrentHashMap}.
 */
public final class OpenTelemetryRuntimeObserver implements RuntimeEventObserver, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryRuntimeObserver.class);

    static final int DEFAULT_MAX_IN_FLIGHT = 1024;
    static final long DEFAULT_SPAN_TTL_SECONDS = 300L; // 5 minutes
    private static final int SUMMARY_TRUNCATION = 200;

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
    private final Duration spanTtl;
    private final int maxInFlight;

    // operationId -> in-flight operation span (with start instant + wall-clock creation time).
    private final ConcurrentHashMap<String, InFlight> operationSpans = new ConcurrentHashMap<>();
    // toolSpanKey -> in-flight tool span.
    private final ConcurrentHashMap<String, InFlight> toolSpans = new ConcurrentHashMap<>();
    // providerSpanKey -> in-flight provider span.
    private final ConcurrentHashMap<String, InFlight> providerSpans = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Metric instruments are built once and reused. Histogram/counter builders are cheap but reusing the built
    // instrument avoids per-event allocation and keeps cardinality controlled by the SDK.
    private final LongHistogram operationDurationHistogram;
    private final LongCounter operationFailureCounter;
    private final LongHistogram toolDurationHistogram;
    private final LongCounter tokenInputCounter;
    private final LongCounter tokenOutputCounter;

    /**
     * Creates an observer with {@link ContentCaptureLevel#METADATA_ONLY}, an empty agent resolver, and default TTL/cap.
     */
    public OpenTelemetryRuntimeObserver(OpenTelemetry openTelemetry) {
        this(openTelemetry, operationId -> "", ContentCaptureLevel.METADATA_ONLY);
    }

    /**
     * Creates an observer with default TTL ({@value #DEFAULT_SPAN_TTL_SECONDS}s) and cap
     * ({@value #DEFAULT_MAX_IN_FLIGHT}).
     */
    public OpenTelemetryRuntimeObserver(
            OpenTelemetry openTelemetry, Function<String, String> agentResolver, ContentCaptureLevel captureLevel) {
        this(
                openTelemetry,
                agentResolver,
                captureLevel,
                Duration.ofSeconds(DEFAULT_SPAN_TTL_SECONDS),
                DEFAULT_MAX_IN_FLIGHT);
    }

    /**
     * Full constructor.
     *
     * @param spanTtl how long an in-flight span may remain unmatched before the TTL sweeper ends and evicts it.
     * @param maxInFlight hard cap on the number of in-flight spans per kind. When exceeded the oldest entry is evicted.
     */
    public OpenTelemetryRuntimeObserver(
            OpenTelemetry openTelemetry,
            Function<String, String> agentResolver,
            ContentCaptureLevel captureLevel,
            Duration spanTtl,
            int maxInFlight) {
        this.meter = openTelemetry.getMeter(METER_NAME);
        this.tracer = openTelemetry.getTracer(TRACER_NAME, TRACER_VERSION);
        this.agentResolver = agentResolver;
        this.captureLevel = captureLevel;
        this.spanTtl = spanTtl == null ? Duration.ofSeconds(DEFAULT_SPAN_TTL_SECONDS) : spanTtl;
        this.maxInFlight = maxInFlight <= 0 ? DEFAULT_MAX_IN_FLIGHT : maxInFlight;
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
            evictExpired();
            dispatch(event);
        } catch (RuntimeException e) {
            // Observability must never change the operation outcome. Swallow and log at DEBUG.
            if (LOG.isDebugEnabled()) {
                LOG.debug("OpenTelemetry observer failed to process event {}", event.type(), e);
            }
        }
    }

    private void dispatch(RuntimeEventRecord event) {
        RuntimeEventType type = event.type();
        if (type == null) {
            return;
        }
        switch (type) {
            case OPERATION_STARTED -> startOperationSpan(event);
            case OPERATION_SUCCEEDED -> endOperationSpan(event, StatusCode.OK, null);
            case OPERATION_FAILED -> endOperationSpan(event, StatusCode.ERROR, errorCode(event));
            case TOOL_STARTED -> startToolSpan(event);
            case TOOL_SUCCEEDED -> endToolSpan(event, StatusCode.OK, null);
            case TOOL_FAILED -> endToolSpan(event, StatusCode.ERROR, errorCode(event));
            case MODEL_STARTED -> startProviderSpan(event);
            case MODEL_SUCCEEDED -> {
                endProviderSpan(event, StatusCode.OK, null);
                recordTokenUsage(event);
            }
            case MODEL_FAILED -> endProviderSpan(event, StatusCode.ERROR, errorCode(event));
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
        putOperation(opId, new InFlight(span, start, System.currentTimeMillis()));
    }

    private void endOperationSpan(RuntimeEventRecord event, StatusCode status, String code) {
        String opId = event.operationId();
        InFlight inFlight = operationSpans.remove(opId);
        if (inFlight == null) {
            return;
        }
        long durationMs = inFlight.startedAt() == null
                ? 0L
                : Math.max(
                        0L,
                        Duration.between(inFlight.startedAt(), event.createdAt())
                                .toMillis());
        inFlight.span().setAttribute("helm.operation.duration_ms", durationMs);
        inFlight.span().setAttribute("helm.operation.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null && !code.isEmpty()) {
            inFlight.span().setAttribute("helm.operation.code", code);
        }
        inFlight.span().setStatus(status);
        inFlight.span().end();
        recordOperationMetric(durationMs, opId, status, code);
    }

    // --- Tool span (child of operation span) ---

    private void startToolSpan(RuntimeEventRecord event) {
        String opId = event.operationId();
        String key = toolSpanKey(event);
        if (key == null) {
            return;
        }
        String toolName = str(event.payload(), "toolName");
        String toolCallId = str(event.payload(), "toolCallId");
        Span parent = operationSpans.get(opId == null ? "" : opId) == null
                ? null
                : operationSpans.get(opId).span();
        Context parentContext =
                parent == null ? Context.current() : Context.current().with(parent);
        Span span = tracer.spanBuilder("helm.tool.call")
                .setParent(parentContext)
                .setAttribute("helm.tool", toolName == null ? "" : toolName)
                .setAttribute("helm.tool.call_id", toolCallId == null ? "" : toolCallId)
                .startSpan();
        captureContent(span, "helm.tool.input", event.payload(), "input");
        putTool(key, new InFlight(span, event.createdAt(), System.currentTimeMillis()));
    }

    private void endToolSpan(RuntimeEventRecord event, StatusCode status, String code) {
        String key = toolSpanKey(event);
        if (key == null) {
            return;
        }
        InFlight inFlight = toolSpans.remove(key);
        if (inFlight == null) {
            return;
        }
        String toolName = str(event.payload(), "toolName");
        long durationMs = event.payload().containsKey("durationMs")
                ? longOf(event.payload().get("durationMs"))
                : inFlight.startedAt() == null
                        ? 0L
                        : Math.max(
                                0L,
                                Duration.between(inFlight.startedAt(), event.createdAt())
                                        .toMillis());
        inFlight.span().setAttribute("helm.tool.duration_ms", durationMs);
        inFlight.span().setAttribute("helm.tool.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null && !code.isEmpty()) {
            inFlight.span().setAttribute("helm.tool.code", code);
        }
        captureContent(inFlight.span(), "helm.tool.output", event.payload(), "output");
        inFlight.span().setStatus(status);
        inFlight.span().end();
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
        String key = providerSpanKey(event);
        if (key == null) {
            return;
        }
        String provider = str(event.payload(), "provider");
        String model = str(event.payload(), "model");
        Span parent = operationSpans.get(opId == null ? "" : opId) == null
                ? null
                : operationSpans.get(opId).span();
        Context parentContext =
                parent == null ? Context.current() : Context.current().with(parent);
        Span span = tracer.spanBuilder("helm.provider.call")
                .setParent(parentContext)
                .setAttribute("helm.provider", provider == null ? "" : provider)
                .setAttribute("helm.model", model == null ? "" : model)
                .startSpan();
        // Model prompt content: the runtime places the prompt under "prompt" (or "input" as a fallback).
        captureContent(span, "helm.prompt", event.payload(), "prompt");
        if (event.payload().get("prompt") == null) {
            captureContent(span, "helm.prompt", event.payload(), "input");
        }
        putProvider(key, new InFlight(span, event.createdAt(), System.currentTimeMillis()));
    }

    private void endProviderSpan(RuntimeEventRecord event, StatusCode status, String code) {
        String key = providerSpanKey(event);
        if (key == null) {
            return;
        }
        InFlight inFlight = providerSpans.remove(key);
        if (inFlight == null) {
            return;
        }
        long durationMs = event.payload().containsKey("durationMs")
                ? longOf(event.payload().get("durationMs"))
                : inFlight.startedAt() == null
                        ? 0L
                        : Math.max(
                                0L,
                                Duration.between(inFlight.startedAt(), event.createdAt())
                                        .toMillis());
        inFlight.span().setAttribute("helm.provider.duration_ms", durationMs);
        inFlight.span().setAttribute("helm.provider.status", status == StatusCode.OK ? "success" : "failure");
        if (code != null && !code.isEmpty()) {
            inFlight.span().setAttribute("helm.provider.code", code);
        }
        inFlight.span().setStatus(status);
        inFlight.span().end();
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
        InFlight inFlight = operationSpans.get(event.operationId());
        if (inFlight == null) {
            return;
        }
        inFlight.span().setAttribute("helm.event." + event.type().type(), event.sequence());
    }

    // --- Orphaned-span reclamation (TTL sweep + cap enforcement) ---

    /**
     * Ends and removes in-flight spans older than {@link #spanTtl}. Called on every {@link #onEvent} for on-access
     * cleanup; cheap because the maps are bounded by {@link #maxInFlight}.
     */
    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - spanTtl.toMillis();
        evictExpired(operationSpans, cutoff, "operation");
        evictExpired(toolSpans, cutoff, "tool");
        evictExpired(providerSpans, cutoff, "provider");
    }

    private void evictExpired(ConcurrentHashMap<String, InFlight> spans, long cutoff, String kind) {
        if (spans.isEmpty()) {
            return;
        }
        for (Map.Entry<String, InFlight> entry : spans.entrySet()) {
            InFlight inFlight = entry.getValue();
            if (inFlight.createdMillis() < cutoff) {
                if (spans.remove(entry.getKey(), inFlight)) {
                    endOrphaned(inFlight.span(), kind);
                    LOG.warn("evicted orphaned {} span older than {} (map size now {})", kind, spanTtl, spans.size());
                }
            }
        }
    }

    private void putOperation(String key, InFlight inFlight) {
        enforceCap(operationSpans, "operation");
        operationSpans.put(key, inFlight);
    }

    private void putTool(String key, InFlight inFlight) {
        enforceCap(toolSpans, "tool");
        toolSpans.put(key, inFlight);
    }

    private void putProvider(String key, InFlight inFlight) {
        enforceCap(providerSpans, "provider");
        providerSpans.put(key, inFlight);
    }

    /** When the map is full, evict the oldest entry (by creation time) to bound memory. */
    private void enforceCap(ConcurrentHashMap<String, InFlight> spans, String kind) {
        if (spans.size() < maxInFlight) {
            return;
        }
        Map.Entry<String, InFlight> oldest = null;
        for (Map.Entry<String, InFlight> entry : spans.entrySet()) {
            if (oldest == null
                    || entry.getValue().createdMillis() < oldest.getValue().createdMillis()) {
                oldest = entry;
            }
        }
        if (oldest != null && spans.remove(oldest.getKey(), oldest.getValue())) {
            endOrphaned(oldest.getValue().span(), kind);
            LOG.warn("evicted oldest {} span to enforce cap {} (map size now {})", kind, maxInFlight, spans.size());
        }
    }

    private static void endOrphaned(Span span, String reason) {
        try {
            span.setAttribute("helm.orphaned", true);
            span.setAttribute("helm.orphan.reason", reason);
            span.setStatus(StatusCode.ERROR);
            span.end();
        } catch (RuntimeException ignored) {
            // best-effort cleanup; never let orphan reclamation throw
        }
    }

    /** Drains every in-flight span with an error status. Idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drain(operationSpans, "operation");
        drain(toolSpans, "tool");
        drain(providerSpans, "provider");
    }

    private void drain(ConcurrentHashMap<String, InFlight> spans, String kind) {
        for (InFlight inFlight : spans.values()) {
            endOrphaned(inFlight.span(), "closed:" + kind);
        }
        spans.clear();
    }

    // --- Content capture ---

    /**
     * Records a redacted snapshot of {@code payload.get(payloadKey)} as a span attribute, honoring
     * {@link ContentCaptureLevel}. SUMMARY truncates to {@value #SUMMARY_TRUNCATION} chars; FULL records the whole
     * value. The value is passed through {@link RedactingEventRedactor} before serialization so adapter-local content
     * capture never re-introduces sensitive content.
     */
    private void captureContent(Span span, String baseAttribute, Map<String, Object> payload, String payloadKey) {
        if (captureLevel == ContentCaptureLevel.METADATA_ONLY) {
            return;
        }
        Object value = payload.get(payloadKey);
        if (value == null) {
            return;
        }
        Object redacted = RedactingEventRedactor.redact(value);
        String text = serialize(redacted);
        String suffix = captureLevel == ContentCaptureLevel.SUMMARY ? ".summary" : ".full";
        if (captureLevel == ContentCaptureLevel.SUMMARY && text.length() > SUMMARY_TRUNCATION) {
            text = text.substring(0, SUMMARY_TRUNCATION);
        }
        span.setAttribute(baseAttribute + suffix, text);
    }

    /**
     * Serializes a redacted payload value to a string for span attributes. Uses Java's default object string form (not
     * JSON) — this is a debugging aid only and SUMMARY/FULL are opt-in.
     */
    private static String serialize(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        return String.valueOf(value);
    }

    // --- Helpers ---

    /**
     * Computes the tool span key from {@code operationId} and {@code toolCallId}. When {@code toolCallId} is missing
     * (the collision-prone case), falls back to {@code event.id()} so each tool call still gets a distinct span.
     */
    private String toolSpanKey(RuntimeEventRecord event) {
        String opId = event.operationId();
        String toolCallId = str(event.payload(), "toolCallId");
        if (toolCallId == null || toolCallId.isBlank()) {
            LOG.warn(
                    "tool event has no toolCallId; falling back to event id for span key (span tracking may be imprecise)");
            return (opId == null ? "" : opId) + ":tool:event:" + event.id();
        }
        return (opId == null ? "" : opId) + ":tool:" + toolCallId;
    }

    private String providerSpanKey(RuntimeEventRecord event) {
        String opId = event.operationId();
        String modelCallId = str(event.payload(), "modelCallId");
        if (modelCallId == null || modelCallId.isBlank()) {
            LOG.warn(
                    "model event has no modelCallId; falling back to event id for span key (span tracking may be imprecise)");
            return (opId == null ? "" : opId) + ":model:event:" + event.id();
        }
        return (opId == null ? "" : opId) + ":model:" + modelCallId;
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

    /** Holder for an in-flight span plus its event start instant and wall-clock creation time. */
    private static final class InFlight {
        private final Span span;
        private final Instant startedAt;
        private final long createdMillis;

        InFlight(Span span, Instant startedAt, long createdMillis) {
            this.span = span;
            this.startedAt = startedAt;
            this.createdMillis = createdMillis;
        }

        Span span() {
            return span;
        }

        Instant startedAt() {
            return startedAt;
        }

        long createdMillis() {
            return createdMillis;
        }
    }
}
