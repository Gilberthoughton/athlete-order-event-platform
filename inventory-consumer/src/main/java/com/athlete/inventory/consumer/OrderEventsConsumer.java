package com.athlete.inventory.consumer;

import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumes order integration events (Avro, deserialized via the Schema Registry) and maintains the
 * fulfillment read model. Delivery is at-least-once, so the listener is idempotent: it records each
 * {@code eventId} in an inbox and skips events already processed. Inbox write and read-model update
 * share one transaction. The consumer depends only on the Avro contract, not on producer code.
 */
@Component
public class OrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private static final String MDC_CORRELATION_ID = "correlationId";

    private final ProcessedEventStore processedEvents;
    private final FulfillmentStore fulfillment;
    private final ConsumerMetrics metrics;

    public OrderEventsConsumer(ProcessedEventStore processedEvents,
                               FulfillmentStore fulfillment,
                               ConsumerMetrics metrics) {
        this.processedEvents = processedEvents;
        this.fulfillment = fulfillment;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${aoep.topics.order-events:order.events}",
            groupId = "${spring.kafka.consumer.group-id:inventory-consumer}")
    @Transactional
    public void onMessage(OrderIntegrationEvent event) {
        String eventType = event.getEventType().name();
        MDC.put(MDC_CORRELATION_ID, correlationId(event));
        try {
            metrics.received(eventType);
            UUID eventId = UUID.fromString(event.getEventId());
            if (!processedEvents.markProcessed(eventId)) {
                metrics.duplicateIgnored(eventType);
                log.debug("duplicate event {} ignored", eventId);
                return;
            }
            handle(event);
            metrics.applied(eventType);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private void handle(OrderIntegrationEvent event) {
        UUID orderId = UUID.fromString(event.getAggregateId());
        switch (event.getEventType()) {
            case ORDER_CONFIRMED -> {
                fulfillment.recordConfirmed(orderId, new BigDecimal(event.getTotalAmount()), event.getCurrency());
                log.info("fulfillment requested for confirmed order {}", orderId);
            }
            case ORDER_CANCELLED -> {
                fulfillment.recordCancelled(orderId, event.getReasonCode());
                log.info("order {} cancelled ({}), released from fulfillment", orderId, event.getReasonCode());
            }
        }
    }

    private static String correlationId(OrderIntegrationEvent event) {
        if (event.getCorrelationId() != null) {
            return event.getCorrelationId();
        }
        return event.getAggregateId() != null ? event.getAggregateId() : "unknown";
    }
}
