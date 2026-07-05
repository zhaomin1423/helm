package io.agent.helm.examples.memorysession;

import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.TypeDescriptor;
import java.util.Map;

/** Deterministic order-status lookup tool used by {@link SupportAgent}. */
public final class OrderStatusTool implements Tool<OrderStatusTool.Query, OrderStatusTool.Status> {
    private static final Map<String, Status> ORDERS = Map.of(
            "A-1001", new Status("A-1001", "SHIPPED", "2026-07-08"),
            "A-1002", new Status("A-1002", "PROCESSING", "2026-07-12"));

    public record Query(String orderId) {}

    public record Status(String orderId, String state, String estimatedDelivery) {}

    @Override
    public String name() {
        return "order_status";
    }

    @Override
    public String description() {
        return "Look up the shipping status of a customer order by order id.";
    }

    @Override
    public TypeDescriptor<Query> inputType() {
        return TypeDescriptor.of(Query.class);
    }

    @Override
    public TypeDescriptor<Status> outputType() {
        return TypeDescriptor.of(Status.class);
    }

    @Override
    public Status execute(ToolContext context, Query input) {
        Status status = ORDERS.get(input.orderId());
        if (status == null) {
            return new Status(input.orderId(), "NOT_FOUND", "");
        }
        return status;
    }
}
