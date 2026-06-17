package com.athlete.inventory.consumer;

import java.util.UUID;

/** Inbox for consumer-side idempotency (ADR 0008). */
public interface ProcessedEventStore {

    /** Records the event id; returns {@code true} if newly seen, {@code false} if a duplicate. */
    boolean markProcessed(UUID eventId);
}
