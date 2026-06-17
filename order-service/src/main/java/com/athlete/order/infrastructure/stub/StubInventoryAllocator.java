package com.athlete.order.infrastructure.stub;

import com.athlete.order.application.port.InventoryAllocator;
import com.athlete.order.domain.model.Allocation;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic stub standing in for the inventory context. Any line whose SKU begins with
 * {@code OOS-} causes the allocation to fail, exercising the saga's failure + compensation path;
 * otherwise every line is allocated to a fixed location.
 */
@Component
public class StubInventoryAllocator implements InventoryAllocator {

    private static final Logger log = LoggerFactory.getLogger(StubInventoryAllocator.class);
    private static final String OUT_OF_STOCK_PREFIX = "OOS-";
    private static final String DEFAULT_LOCATION = "DC-PA-1";

    @Override
    public Result allocate(OrderId orderId, List<OrderLine> lines) {
        boolean outOfStock = lines.stream()
                .anyMatch(line -> line.sku().value().startsWith(OUT_OF_STOCK_PREFIX));
        if (outOfStock) {
            return Result.failed("OUT_OF_STOCK");
        }
        List<Allocation> allocations = lines.stream()
                .map(line -> new Allocation(line.sku(), line.quantity().value(), DEFAULT_LOCATION))
                .toList();
        log.info("allocated {} line(s) for order {}", allocations.size(), orderId);
        return Result.allocated(allocations);
    }
}
