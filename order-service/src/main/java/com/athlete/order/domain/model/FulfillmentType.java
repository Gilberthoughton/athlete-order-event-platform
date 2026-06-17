package com.athlete.order.domain.model;

/** How an order line is fulfilled in an omnichannel retail context. */
public enum FulfillmentType {
    /** Shipped to the athlete's address. */
    SHIP,
    /** Buy online, pick up in store. */
    BOPIS,
    /** Shipped from a store location rather than a distribution center. */
    SHIP_FROM_STORE
}
