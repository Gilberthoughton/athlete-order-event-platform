package com.athlete.inventory.consumer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The downstream read model this consumer maintains from order events. */
public interface FulfillmentStore {

    void recordConfirmed(UUID orderId, BigDecimal totalAmount, String currency);

    void recordCancelled(UUID orderId, String reasonCode);

    List<FulfillmentView> findAll();

    record FulfillmentView(UUID orderId, String status, BigDecimal totalAmount, String currency, String note) {
    }
}
