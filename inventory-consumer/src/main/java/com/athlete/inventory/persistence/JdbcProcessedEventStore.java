package com.athlete.inventory.persistence;

import com.athlete.inventory.consumer.ProcessedEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Inbox-table idempotency: the primary key rejects duplicates atomically. */
@Repository
public class JdbcProcessedEventStore implements ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(UUID eventId) {
        int inserted = jdbc.update(
                "INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING",
                eventId);
        return inserted == 1;
    }
}
