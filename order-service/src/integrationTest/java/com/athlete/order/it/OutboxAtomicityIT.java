package com.athlete.order.it;

import com.athlete.order.application.OrderApplicationService;
import com.athlete.order.application.port.EventStore;
import com.athlete.order.application.port.OutboxRepository;
import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.FulfillmentType;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.Quantity;
import com.athlete.order.domain.model.Sku;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * Proves the event-store append and the outbox write commit or roll back together. With the outbox
 * write forced to fail, the domain events produced in the same transaction must not survive.
 */
@SpringBootTest(properties = {
        "aoep.outbox.poll-interval-ms=3600000",
        "aoep.projection.poll-interval-ms=3600000"
})
class OutboxAtomicityIT extends AbstractIntegrationTest {

    @MockBean
    OutboxRepository outbox;

    @Autowired
    EventStore eventStore;

    @Autowired
    OrderApplicationService orders;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void event_append_and_outbox_write_are_atomic() {
        // Seed an order by writing OrderPlaced directly, so the confirmation saga does not run.
        OrderId id = OrderId.newId();
        OrderLine line = new OrderLine(new Sku("SHOE-1"), Quantity.of(1), Money.of("50.00", "USD"), FulfillmentType.SHIP);
        eventStore.append(id, -1L, List.of(new DomainEvent.OrderPlaced(
                id, new AthleteId(UUID.randomUUID()), List.of(line), Money.of("50.00", "USD"), Instant.now())));
        assertThat(eventTypes(id)).containsExactly("OrderPlaced");

        // The next state change produces an integration event; force its outbox write to fail.
        doThrow(new RuntimeException("outbox unavailable")).when(outbox).append(any(), anyList());

        assertThatThrownBy(() -> orders.recordPaymentDeclined(id, "TEST_FAILURE"))
                .isInstanceOf(RuntimeException.class);

        // The decline/cancel events were rolled back with the failed outbox write.
        assertThat(eventTypes(id)).containsExactly("OrderPlaced");
    }

    private List<String> eventTypes(OrderId id) {
        return jdbc.queryForList(
                "SELECT event_type FROM events WHERE aggregate_id = ? ORDER BY sequence_no",
                String.class, id.value());
    }
}
