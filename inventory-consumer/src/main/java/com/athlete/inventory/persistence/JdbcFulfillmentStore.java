package com.athlete.inventory.persistence;

import com.athlete.inventory.consumer.FulfillmentStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Maintains the fulfillment read model with idempotent upserts. */
@Repository
public class JdbcFulfillmentStore implements FulfillmentStore {

    private final JdbcTemplate jdbc;

    public JdbcFulfillmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordConfirmed(UUID orderId, BigDecimal totalAmount, String currency) {
        jdbc.update("""
                INSERT INTO fulfillment_order (order_id, status, total_amount, currency, note, updated_at)
                VALUES (?, 'CONFIRMED', ?, ?, 'fulfillment requested', now())
                ON CONFLICT (order_id) DO UPDATE
                SET status = 'CONFIRMED',
                    total_amount = EXCLUDED.total_amount,
                    currency = EXCLUDED.currency,
                    note = EXCLUDED.note,
                    updated_at = now()
                """, orderId, totalAmount, currency);
    }

    @Override
    public void recordCancelled(UUID orderId, String reasonCode) {
        jdbc.update("""
                INSERT INTO fulfillment_order (order_id, status, total_amount, currency, note, updated_at)
                VALUES (?, 'CANCELLED', NULL, NULL, ?, now())
                ON CONFLICT (order_id) DO UPDATE
                SET status = 'CANCELLED',
                    note = EXCLUDED.note,
                    updated_at = now()
                """, orderId, "cancelled: " + reasonCode);
    }

    @Override
    public List<FulfillmentView> findAll() {
        return jdbc.query("""
                SELECT order_id, status, total_amount, currency, note
                FROM fulfillment_order
                ORDER BY updated_at DESC
                """,
                (rs, n) -> new FulfillmentView(
                        rs.getObject("order_id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("currency"),
                        rs.getString("note")));
    }
}
