package io.agent.helm.runtime.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agent.helm.core.error.PersistenceException;
import io.agent.helm.runtime.AgentPromptRequest;
import java.util.Map;

/**
 * JSON codec for the runtime: encodes/decodes {@link AgentPromptRequest} and arbitrary workflow input/output to/from
 * the raw-JSON strings stored in {@link io.agent.helm.core.store.OperationRecord} and
 * {@link io.agent.helm.core.store.WorkflowRunRecord}. Errors are surfaced as {@link PersistenceException} so callers
 * can handle them uniformly.
 */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = createMapper();

    private JsonCodec() {}

    /** Encodes a value to its JSON string form. */
    public static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to JSON-encode value: " + e.getMessage(),
                    Map.of("type", value == null ? "null" : value.getClass().getName()),
                    Map.of(),
                    e);
        }
    }

    /** Decodes a JSON string into the given type. */
    public static <T> T decode(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to JSON-decode value: " + e.getMessage(),
                    Map.of("type", type.getName()),
                    Map.of("json", json == null ? "null" : json),
                    e);
        }
    }

    /** Encodes an {@link AgentPromptRequest} for durable persistence/recovery. */
    public static String encodeRequest(AgentPromptRequest request) {
        return encode(request);
    }

    /** Decodes an {@link AgentPromptRequest} from the JSON stored in an operation record's input. */
    public static AgentPromptRequest decodeRequest(String json) {
        return decode(json, AgentPromptRequest.class);
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
