package com.athlete.order.application.port;

import com.athlete.order.domain.model.Allocation;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;

import java.util.List;

/**
 * Port to the inventory context, driven by the confirmation saga (ADR 0009). A failed allocation
 * is a normal {@link Result}; transport problems are signalled by throwing
 * {@link com.athlete.order.application.TransientFailureException} so the saga can retry.
 */
public interface InventoryAllocator {

    Result allocate(OrderId orderId, List<OrderLine> lines);

    record Result(boolean failed, List<Allocation> allocations, String reasonCode) {

        public static Result allocated(List<Allocation> allocations) {
            return new Result(false, List.copyOf(allocations), null);
        }

        public static Result failed(String reasonCode) {
            return new Result(true, List.of(), reasonCode);
        }
    }
}
