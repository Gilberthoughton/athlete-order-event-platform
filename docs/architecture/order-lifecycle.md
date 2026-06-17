# Order Lifecycle

How an `Order` moves through its states, and the runtime sequences behind the two most
important flows. State is **derived** from the event stream — these diagrams describe the
*projection* of current status, not a mutable status column.

## State model

```mermaid
stateDiagram-v2
    [*] --> Placed: PlaceOrder
    Placed --> AwaitingConfirmation: auth + allocation requested
    AwaitingConfirmation --> Confirmed: PaymentAuthorized + InventoryAllocated
    AwaitingConfirmation --> Rejected: PaymentDeclined / AllocationFailed
    Confirmed --> PartiallyShipped: ParcelShipped (some lines)
    Confirmed --> Shipped: ParcelShipped (all lines)
    PartiallyShipped --> Shipped: remaining ParcelShipped
    Shipped --> Delivered: OrderDelivered
    Placed --> Cancelled: CancelOrder
    AwaitingConfirmation --> Cancelled: CancelOrder
    Confirmed --> Cancelled: CancelOrder
    PartiallyShipped --> Cancelled: CancelOrder (remaining lines)
    Delivered --> ReturnRequested: RequestReturn
    Rejected --> [*]
    Cancelled --> [*]
    ReturnRequested --> [*]
    Delivered --> [*]
```

**Invariants enforced by the aggregate (examples):**

- Cannot `Confirm` without both payment authorization and inventory allocation.
- Cannot `Cancel` lines that have already shipped (only remaining lines are cancellable).
- Cannot `RequestReturn` before `Delivered`.
- A command targeting a stale aggregate version is rejected with a concurrency conflict
  ([ADR 0005](../adr/0005-optimistic-concurrency-control.md)).

These invariants live in the **pure aggregate**, decided against state folded from prior
events — making them exhaustively testable with Given-When-Then unit tests.

---

## Sequence — place & confirm an order (write path + reliable publish)

```mermaid
sequenceDiagram
    participant C as Client
    participant API as Command API
    participant App as OrderApplicationService
    participant Agg as Order aggregate
    participant ES as Event store (Postgres)
    participant OB as Outbox (Postgres)
    participant R as Relay
    participant K as Kafka

    C->>API: POST /orders (PlaceOrder, idempotencyKey)
    API->>App: handle(PlaceOrder)
    App->>ES: load events for orderId (none yet)
    App->>Agg: decide(PlaceOrder)
    Agg-->>App: [OrderPlaced, AuthRequested, AllocationRequested]
    rect rgb(235,245,255)
    note over App,OB: single Postgres transaction
    App->>ES: append domain events (seq 0..n)
    App->>OB: write outbox row(s)
    end
    App-->>API: 201 Created (orderId, version)
    API-->>C: 201 + Location

    Note over R,K: asynchronously, after commit
    R->>OB: read undispatched (CDC or poll)
    R->>K: publish OrderConfirmed (key = orderId)
    R->>OB: mark dispatched
```

The client gets a fast, durable acknowledgement the moment the transaction commits.
Publishing to Kafka happens **after** commit, decoupled, and is retried safely because the
outbox row is the durable record of intent.

---

## Sequence — downstream reaction (at-least-once + idempotency)

```mermaid
sequenceDiagram
    participant K as Kafka (order.events)
    participant Cons as Inventory consumer
    participant Inbox as Processed-events table
    participant DB as Inventory DB

    K->>Cons: OrderConfirmed (eventId, key=orderId)
    Cons->>Inbox: INSERT eventId (unique)
    alt eventId already present (duplicate delivery)
        Inbox-->>Cons: conflict -> skip side effect
    else first time
        rect rgb(235,245,255)
        note over Cons,DB: one transaction
        Cons->>DB: commit reservation (idempotent upsert)
        Cons->>Inbox: keep eventId
        end
    end
    Cons->>K: commit offset
```

Because delivery is at-least-once ([ADR 0008](../adr/0008-idempotent-consumers.md)), the
consumer dedupes on `eventId`; a redelivered event is recognized and produces no extra effect.

---

## Replay flows (realizing the "replay" goal)

- **Aggregate replay:** load all events for an `orderId` and fold them → current `Order`.
  This is the normal rehydration path on every command.
- **Projection rebuild:** truncate (or version) a read model and re-fold the entire event
  stream from `sequence_no = 0` → useful after a projection bug fix or a new read model.
- **Point-in-time replay:** fold events up to a chosen `occurredAt`/`sequenceNo` → "what did
  this order look like last Tuesday?" — a tangible demonstration of immutable history's value.
