# Domain Glossary (Ubiquitous Language)

A shared, precise vocabulary for the order domain. Code, events, APIs, and these documents
all use these exact terms. The language is intentionally that of **omnichannel sporting-goods
retail** — the realism is part of the design.

## Actors

| Term | Definition |
|------|------------|
| **Athlete** | The customer placing and tracking an order. (Retail-domain term for the end customer.) |
| **Customer Service Rep (CSR)** | Internal user who looks up and may cancel orders on an athlete's behalf. |

## Core concepts

| Term | Definition |
|------|------------|
| **Order** | The aggregate and consistency boundary. Its state is derived by folding its event stream. Identified by an `orderId`. |
| **Order line** | A single SKU + quantity + unit price within an order. |
| **SKU** | Stock Keeping Unit — the identifier for a sellable product variant (model/size/color). |
| **Money** | A value object: amount + currency. Arithmetic is currency-checked; never a bare `double`. |
| **Quantity** | A value object: a positive integer count. |
| **Fulfillment type** | How an order line is fulfilled: `SHIP` (to address), `BOPIS` (buy online, pick up in store), or `SHIP_FROM_STORE`. |
| **Parcel / shipment** | A physically shipped subset of order lines. An order may produce **multiple parcels** (split shipment). |

## Lifecycle & process terms

| Term | Definition |
|------|------------|
| **Command** | An imperative request to change an order (e.g. `PlaceOrder`, `CancelOrder`). May be rejected. Named in the imperative. |
| **Domain event** | A past-tense fact the `Order` aggregate emits to record a state change (e.g. `OrderPlaced`). Internal; lives only in the event store. |
| **Integration event** | A past-tense fact published to other contexts as a public, versioned contract (e.g. `OrderConfirmed`). |
| **Allocation** | Reserving inventory for an order's lines. Modeled as an event-driven interaction with the inventory context. |
| **Authorization** | Reserving funds with the payment gateway (not a capture). |
| **Confirmation** | The point at which payment is authorized and inventory allocated; the order becomes a firm commitment. |
| **Split shipment** | Fulfilling one order via multiple parcels, possibly from different locations and at different times. |
| **Partial fulfillment** | Shipping some order lines while others remain pending or back-ordered. |
| **Cancellation** | Terminating an order (or remaining lines) before fulfillment completes. |
| **Return** | An athlete-initiated request to send delivered goods back; recorded as events for history and analytics. |

## Event-sourcing terms

| Term | Definition |
|------|------------|
| **Event store** | The append-only PostgreSQL table that is the system of record. |
| **Aggregate / sequence version** | The per-aggregate monotonic `sequence_no`; also the optimistic-concurrency token. |
| **Rehydration** | Reconstructing an aggregate's current state by replaying its events. |
| **Projection / read model** | A query-optimized view derived from events; eventually consistent; rebuildable by replay. |
| **Replay** | Re-deriving state (an aggregate or a projection) by folding events — from the beginning or to a point in time. |
| **Outbox** | The table written in the same transaction as events, from which the relay publishes to Kafka. |
| **Upcaster** | A function that transforms an older on-the-wire event version into the current in-memory shape. |
| **Correlation ID / Causation ID** | Metadata linking events: *correlation* groups all events of one business flow; *causation* points to the specific event/command that directly caused this one. |
| **Idempotency key** | A client-supplied (commands) or `eventId`-based (consumers) key ensuring repeated processing has no extra effect. |

## Naming conventions

- **Commands** are imperative: `PlaceOrder`, `AllocateInventory`, `ShipParcel`.
- **Events** are past tense: `OrderPlaced`, `InventoryAllocated`, `ParcelShipped`.
- **Value objects** are immutable and self-validating: `Money`, `Sku`, `Quantity`.
- An `Order` aggregate has **behavior** (`order.cancel()` emits an event); it is never an
  anemic bag of getters/setters.
