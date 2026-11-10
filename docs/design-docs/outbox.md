# Publishing events: the transactional outbox

Status: accepted, 2026-11-10. Applies to every event this service publishes.

## Problem
A state change in PostgreSQL and the Kafka event that announces it cannot be written in one transaction. Whichever is written second can be lost:
- Save first, then publish: the publish fails, the SIM is active, billing never hears about it.
- Publish first, then save: the save fails, billing charges, this service does not see the SIM as active, and nothing repairs it.

## Rule
The state change and an outbox row are written in the same database transaction. A separate relay reads committed outbox rows and publishes them to Kafka. `service` never talks to Kafka.

## Write path
1. A `service` method annotated `@Transactional` changes the state (for example sets `activated_at`) through its repository.
2. In the same method it inserts one outbox row describing the event.
3. The transaction commits both or neither.

## Outbox table
Created by a Flyway migration.

| Column | Type | Meaning |
|---|---|---|
| `id` | `uuid` primary key | also the event's `eventId` |
| `aggregate_id` | `varchar` | the entity the event is about, used as the Kafka key (ICCID for SIM events) |
| `topic` | `varchar` | target topic, for example `sim.activated` |
| `payload` | `jsonb` | the event exactly as it will be published |
| `created_at` | `timestamptz` | from the injected `Clock` |
| `claimed_until` | `timestamptz`, nullable | set while a relay instance is publishing the row; the row's lease |
| `sent_at` | `timestamptz`, nullable | null until Kafka acknowledged the publish |

## Relay
The relay never waits for Kafka inside a database transaction. A transaction that stays open while Kafka is slow holds a pooled connection and row locks for as long as Kafka takes, and a crash in the middle re-sends the whole batch. So one run has three steps, and only steps 1 and 3 touch the database, each in its own short transaction.

1. **Claim** (short transaction). Select up to 100 rows `WHERE sent_at IS NULL AND (claimed_until IS NULL OR claimed_until < now) ORDER BY created_at` with `FOR UPDATE SKIP LOCKED`, set `claimed_until = now + 30 seconds`, commit. The row locks last only for this transaction; after commit the lease in `claimed_until` keeps other relay instances away.
2. **Publish** (no transaction). Send all claimed rows to Kafka, then wait for all acknowledgements, each with a timeout shorter than the lease (10 seconds).
3. **Mark** (short transaction). For acknowledged rows set `sent_at = now`. For failed rows set `claimed_until = null` so the next run retries them.

If the relay crashes between steps 2 and 3, the lease expires after 30 seconds, another run claims the rows again and publishes them a second time. That is the at-least-once case below.

### Where the relay code lives
The scheduled trigger in `stream` runs the three steps in order: it calls a `service` method to claim, the Kafka publisher in `stream` to publish, and a second `service` method to mark. Claim and mark are separate `@Transactional` service methods called from the trigger. Do not call them from another method of the same service class: Spring does not apply `@Transactional` to a call from inside the same object, so the transaction would silently not exist.

### Ordering
Rows are claimed in creation order, but a failed row is retried in a later run while newer rows may already be sent. There is no global ordering guarantee. Today every SIM has a single activation event, so this does not matter yet; an event type that needs per-aggregate order must say so here first.

## Delivery guarantee
At least once. If the relay publishes and then crashes before step 3, the row is published again after its lease expires. Consumers must drop duplicates by `eventId`; every event carries it.

## Failure cases
| What fails | Result |
|---|---|
| Saving the state or inserting the outbox row | Transaction rolls back; no state change, no event |
| Kafka unavailable or slow | No transaction is open while waiting; failed rows are released and retried next run |
| Relay crashes after publish, before marking | Lease expires; row published again; consumer drops the duplicate by `eventId` |
| Two relay instances run at once | Each claims different rows (`SKIP LOCKED` during claim, lease after it) |

## Tests expected for any new event
- The state change and the outbox row commit together, and a failing outbox insert leaves no state change (real PostgreSQL).
- The relay publishes to a real Kafka and marks the row sent (Testcontainers Kafka).
- No database transaction is active while the publisher runs (check `TransactionSynchronizationManager.isActualTransactionActive()` inside a test publisher).
- A claimed row is not claimed again by a second run until its lease expires, and is claimed again after it expires.
- A failing publish leaves the row pending and a later run sends it.
- Two events have two different `eventId` values.

## Not decided yet
- Deleting old sent rows. Until then the table grows; fine for the lab.
