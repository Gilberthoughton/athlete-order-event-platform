package com.athlete.order.infrastructure.config;

/** Topic names for the public order integration-event contract. */
public final class KafkaTopics {

    /** All order integration events, keyed by orderId for per-aggregate ordering (ADR 0007). */
    public static final String ORDER_EVENTS = "order.events";

    private KafkaTopics() {
    }
}
