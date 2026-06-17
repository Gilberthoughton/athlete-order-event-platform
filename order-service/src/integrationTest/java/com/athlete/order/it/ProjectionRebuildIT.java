package com.athlete.order.it;

import com.athlete.order.application.OrderApplicationService;
import com.athlete.order.application.PlaceOrderCommand;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.FulfillmentType;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.Quantity;
import com.athlete.order.domain.model.Sku;
import com.athlete.order.infrastructure.projection.OrderSummaryProjector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the read model is a derived, disposable view: cleared and then rebuilt deterministically
 * by replaying the event stream from position 0 (realizing the "replay" goal end to end).
 */
@SpringBootTest(properties = {
        "aoep.outbox.poll-interval-ms=3600000",
        "aoep.projection.poll-interval-ms=3600000"
})
class ProjectionRebuildIT extends AbstractIntegrationTest {

    @Autowired
    OrderApplicationService orders;

    @Autowired
    OrderSummaryProjector projector;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void read_model_is_cleared_and_rebuilt_by_replaying_events() {
        UUID confirmed = placeOrder("SHOE-1", "60.00");      // saga confirms
        UUID cancelled = placeOrder("OOS-SHOE-9", "60.00");  // saga cancels (out of stock)

        pumpProjector();
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");

        // Drop the read-model rows and reset the projection checkpoint to replay from the start.
        jdbc.update("DELETE FROM rm_order_summary WHERE order_id IN (?, ?)", confirmed, cancelled);
        jdbc.update("DELETE FROM projection_checkpoints WHERE projection_name = 'order_summary'");
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM rm_order_summary WHERE order_id IN (?, ?)", Integer.class, confirmed, cancelled);
        assertThat(remaining).isZero();

        // Replaying the event stream rebuilds the projection identically.
        pumpProjector();
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    }

    private UUID placeOrder(String sku, String unitPrice) {
        OrderLine line = new OrderLine(new Sku(sku), Quantity.of(1), Money.of(unitPrice, "USD"), FulfillmentType.SHIP);
        return orders.placeOrder(new PlaceOrderCommand(new AthleteId(UUID.randomUUID()), List.of(line))).value();
    }

    private void pumpProjector() {
        for (int i = 0; i < 5; i++) {
            projector.project();
        }
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM rm_order_summary WHERE order_id = ?", String.class, orderId);
    }
}
