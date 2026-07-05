package io.agent.helm.runtime.memory;

import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.JsonSchema;
import io.agent.helm.core.type.TypeDescriptor;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Built-in tool that lets the model persist a long-term memory for the current agent scope. The runtime registers it
 * automatically when a {@link MemoryStore} is configured. Input is accepted either as a typed {@link Input} record
 * (fake providers, tests) or as a JSON object map (real providers).
 */
public final class SaveMemoryTool implements Tool<Object, String> {
    public static final String NAME = "save_memory";

    private final MemoryStore store;
    private final String scopeId;

    public SaveMemoryTool(MemoryStore store, String scopeId) {
        this.store = store;
        this.scopeId = scopeId;
    }

    public record Input(String subject, String content) {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Store a long-term memory (a durable fact or user preference) that should be "
                + "remembered across future sessions.";
    }

    @Override
    public TypeDescriptor<Object> inputType() {
        return TypeDescriptor.of(Object.class);
    }

    @Override
    public JsonSchema inputSchema() {
        return JsonSchema.from(TypeDescriptor.of(Input.class));
    }

    @Override
    public TypeDescriptor<String> outputType() {
        return TypeDescriptor.of(String.class);
    }

    @Override
    public String execute(ToolContext context, Object input) {
        Input command = coerce(input);
        String id = "mem_" + UUID.randomUUID();
        store.save(new MemoryRecord(id, scopeId, command.subject(), command.content(), Instant.now()));
        return id;
    }

    private static Input coerce(Object input) {
        if (input instanceof Input typed) {
            return typed;
        }
        if (input instanceof Map<?, ?> map) {
            return new Input(stringValue(map.get("subject")), stringValue(map.get("content")));
        }
        throw new IllegalArgumentException("Unsupported save_memory input: " + input);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
