package com.athlete.inventory.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumes order integration events and maintains the fulfillment read model. Delivery is
 * at-least-once, so the listener is idempotent: it records each {@code eventId} in an inbox and
 * skips events it has already processed. Inbox write and read-model update share one transaction.
 */
@Component
public class OrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final ProcessedEventStore processedEvents;
    private final FulfillmentStore fulfillment;
    private final ObjectMapper mapper;

    public OrderEventsConsumer(ProcessedEventStore processedEvents,
                               FulfillmentStore fulfillment,
                               ObjectMapper mapper) {
        this.processedEvents = processedEvents;
        this.fulfillment = fulfillment;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${aoep.topics.order-events:order.events}",
            groupId = "${spring.kafka.consumer.group-id:inventory-consumer}")
    @Transactional
    public void onMessage(String message) {
        OrderEventEnvelope envelope = parse(message);
        if (!processedEvents.markProcessed(envelope.eventId())) {
            log.debug("duplicate event {} ignored", envelope.eventId());
            return;
        }
        handle(envelope);
    }

    private void handle(OrderEventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        switch (envelope.eventType()) {
            case "OrderConfirmed" -> {
                JsonNode total = payload.path("total");
                BigDecimal amount = new BigDecimal(total.path("amount").asText("0"));
                String currency = total.path("currency").asText("USD");
                fulfillment.recordConfirmed(envelope.aggregateId(), amount, currency);
                log.info("fulfillment requested for confirmed order {}", envelope.aggregateId());
            }
            case "OrderCancelled" -> {
                String reason = payload.path("reasonCode").asText("UNKNOWN");
                fulfillment.recordCancelled(envelope.aggregateId(), reason);
                log.info("order {} cancelled ({}), released from fulfillment", envelope.aggregateId(), reason);
            }
            default -> log.debug("ignoring unhandled event type {}", envelope.eventType());
        }
    }

    private OrderEventEnvelope parse(String message) {
        try {
            return mapper.readValue(message, OrderEventEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse order event envelope", e);
        }
    }
}
