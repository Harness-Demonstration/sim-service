# Publishing events: the transactional outbox

Status: accepted, 2026-11-10. Applies to every event this service publishes.

## Problem
A state change in PostgreSQL and the Kafka event that announces it cannot be written in one transaction. Whichever is written second can be lost:
- Save first, then publish: the publish fails, the SIM is active, billing never hears about it.
- Publish first, then save: the save fails, billing charges, this service does not see the SIM as active, and nothing repairs it.

## Rule
This is the Transactional outbox pattern as described on microservices.io, with the relay implemented as a polling publisher.

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
| `aggregate_sequence` | `bigint` | event order within the aggregate; a later event waits for every earlier event to be acknowledged |
| `topic` | `varchar` | target topic, for example `sim.activated` |
| `payload` | `jsonb` | the event exactly as it will be published |
| `created_at` | `timestamptz` | from the injected `Clock` |
| `sent_at` | `timestamptz`, nullable | null until Kafka acknowledged the publish |

## Relay
- Runs on a schedule, every second.
- In one transaction: selects up to 100 unsent rows for which no earlier sequence on the same aggregate is pending, ordered by `created_at`, with `FOR UPDATE SKIP LOCKED`; sends them to Kafka, waits for acknowledgements together, sets `sent_at` on acknowledged rows, and commits.
- Send first, wait after: Kafka handles the sends in parallel, so the transaction stays open for about one round trip to Kafka, not one round trip per row. Never wait for each send before sending the next.
- `SKIP LOCKED` lets several instances of the service run the relay at once without publishing the same row twice at the same time.
- Rows whose send failed or timed out keep `sent_at = null` and the next run retries them.

### Known limit
The transaction is still open while the relay waits for Kafka, at most the acknowledgement timeout (10 seconds). If Kafka is slow, that holds one pooled connection and the row locks for that long, and a crash before commit re-sends the whole batch. Accepted for now. The next step, if this becomes a problem, is to claim rows with a lease in one short transaction, publish outside any transaction, and mark them sent in a second short transaction.

### Where the relay code lives
The layer rules apply. The scheduled trigger and the Kafka publisher live in `stream`. Reading and marking outbox rows is a `service` method that uses a repository. `stream` calls that service method and passes the publisher.

## Ordering
Every event has an increasing `aggregate_sequence`. The relay does not claim an event while an earlier sequence for that aggregate is pending, so a failed activation blocks deactivation (including across relay instances and retries). Once the earlier event is acknowledged and committed, the next sequence is eligible in a later run. Events for different aggregates can still be published concurrently.

## Delivery guarantee
At least once. If the relay publishes and then crashes before setting `sent_at`, the next run publishes the row again. Consumers must drop duplicates by `eventId`; every event carries it. Dead-letter records carry the ID in a Kafka header, alongside the reason, so their original value bytes and key remain unchanged. The outbox stores these bytes encoded in a JSON envelope until the relay publishes them.

## Failure cases
| What fails | Result |
|---|---|
| Saving the state or inserting the outbox row | Transaction rolls back; no state change, no event |
| Kafka unavailable | Rows stay pending; published when Kafka is back |
| Relay crashes after publish, before `sent_at` | Event published twice; consumer drops the duplicate by `eventId` |

## Tests expected for any new event
- The state change and the outbox row commit together, and a failing outbox insert leaves no state change (real PostgreSQL).
- The relay publishes to a real Kafka and marks the row sent (Testcontainers Kafka).
- A failing publish leaves the row pending and a later run sends it.
- A failed earlier event blocks later events for the same aggregate until it is acknowledged.
- Two events have two different `eventId` values.

## Not decided yet
- Deleting old sent rows. Until then the table grows; fine for the lab.
