package com.athlete.inventory.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The consumer's own view of the integration-event envelope. Deliberately separate from the
 * producer's types (ADR 0003): it reads only the fields it needs and tolerates unknown ones, so
 * the producer can evolve the contract additively without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventEnvelope(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        UUID correlationId,
        JsonNode payload) {
}
