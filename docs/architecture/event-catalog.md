# Event Catalog

The canonical list of events in the platform, split into **internal domain events** (event
store only) and **public integration events** (Kafka contract). The split is mandated by
[ADR 0003](../adr/0003-separate-domain-and-integration-events.md). All events share a common
metadata envelope.

## Metadata envelope (every event)

| Field | Type | Purpose |
|-------|------|---------|
| `eventId` | UUID | Globally unique id; the basis for consumer idempotency ([ADR 0008](../adr/0008-idempotent-consumers.md)). |
| `eventType` | string | e.g. `OrderPlaced`. |
| `schemaVersion` | int | Event schema version for evolution/upcasting ([ADR 0004](../adr/0004-avro-schema-registry-backward-compat.md)). |
| `aggregateId` | UUID | The `orderId` this event belongs to. |
| `sequenceNo` | long | Monotonic position within the aggregate; the concurrency token ([ADR 0005](../adr/0005-optimistic-concurrency-control.md)). |
| `occurredAt` | timestamp (UTC) | When the fact happened. |
| `correlationId` | UUID | Groups all events in one business flow. |
| `causationId` | UUID | The id of the command/event that directly caused this one. |

> Correlation and causation IDs turn the event log into a traceable causal graph — invaluable
> for debugging distributed flows and a strong signal of production event-driven experience.

---

## Domain events (internal — event store only)

These are fine-grained and free to evolve with the model. They are **never** published to Kafka.

| Domain event | Emitted when | Key payload |
|--------------|--------------|-------------|
| `OrderPlaced` | An athlete submits a valid order. | `athleteId`, `lines[]` (sku, qty, unitPrice, fulfillmentType), `totals`, `idempotencyKey` |
| `PaymentAuthorizationRequested` | The order requests an auth from the gateway. | `amount`, `paymentMethodRef` |
| `PaymentAuthorized` | The gateway approves the authorization. | `authorizationId`, `amount` |
| `PaymentAuthorizationDeclined` | The gateway declines. | `reasonCode` |
| `InventoryAllocationRequested` | Allocation is requested for order lines. | `lines[]` |
| `InventoryAllocated` | All lines are allocated. | `allocations[]` (sku, qty, locationId) |
| `InventoryAllocationFailed` | One or more lines cannot be allocated. | `failedLines[]`, `reasonCode` |
| `OrderConfirmedInternally` | Payment authorized **and** inventory allocated. | (derived state marker) |
| `OrderLineAdjusted` | A line qty/price is corrected before confirmation. | `sku`, `previous`, `current` |
| `ParcelShipped` | A subset of lines ships. | `parcelId`, `lines[]`, `carrier`, `trackingNumber`, `fromLocationId` |
| `OrderDelivered` | Carrier confirms delivery of all parcels. | `deliveredAt` |
| `OrderCancelled` | The order (or remaining lines) is cancelled. | `reasonCode`, `cancelledLines[]` |
| `ReturnRequested` | An athlete requests a return. | `lines[]`, `reasonCode` |

---

## Integration events (public — Kafka contract)

Coarse-grained, intentionally designed, and **versioned**. These are what other contexts may
depend on. Topic naming: `order.events.<eventType>` (or a single keyed `order.events` topic;
finalized in Phase 3). All keyed by `orderId` ([ADR 0007](../adr/0007-partition-by-order-id.md)).

| Integration event | Published when | Consumed by (examples) | Translated from |
|-------------------|----------------|------------------------|-----------------|
| `OrderConfirmed` | Order becomes a firm commitment (paid + allocated). | Inventory (commit reservation), Fulfillment (begin pick/pack), Notifications, Analytics | `OrderConfirmedInternally` (+ `OrderPlaced` context) |
| `OrderShipped` | A parcel ships (one per parcel — supports split shipment). | Notifications (tracking email), Analytics | `ParcelShipped` |
| `OrderDelivered` | All parcels delivered. | Notifications, Analytics, Loyalty | `OrderDelivered` |
| `OrderCancelled` | Order/remaining lines cancelled. | Inventory (release), Payments (void auth), Notifications, Analytics | `OrderCancelled` |
| `OrderReturnRequested` | Return requested. | Fulfillment (RMA), Analytics | `ReturnRequested` |

### Why the lists differ

- `OrderPlaced` is **not** an integration event — downstream contexts care about a *confirmed*
  order, not an unpaid, unallocated submission. Publishing `OrderPlaced` would leak an internal,
  not-yet-actionable state and couple consumers to our pre-confirmation flow.
- `PaymentAuthorized` / `InventoryAllocated` are **internal steps** of reaching confirmation; the
  public contract exposes only the meaningful business outcome, `OrderConfirmed`.
- One domain `ParcelShipped` maps to one public `OrderShipped`, naturally modeling split shipments
  as multiple shipment events for the same `orderId`.

## Versioning policy (integration events)

- Backward-compatible changes (add optional/defaulted field) keep the same event with a bumped
  minor `schemaVersion`.
- Breaking changes create a new event version (`OrderConfirmed.v2`); both run until consumers migrate.
- CI validates every schema change against the Schema Registry's `BACKWARD` rule and fails the build on violation.
