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
| `sent_at` | `timestamptz`, nullable | null until Kafka acknowledged the publish |

## Relay
- Runs on a schedule, every second.
- In one transaction: selects up to 100 rows `WHERE sent_at IS NULL ORDER BY created_at` with `FOR UPDATE SKIP LOCKED`, publishes each, waits for Kafka's acknowledgement, sets `sent_at`, commits.
- `SKIP LOCKED` lets several instances of the service run the relay at once without publishing the same row twice at the same time.
- If a publish fails, the row keeps `sent_at = null` and the next run retries it. The relay stops the batch at the first failure so ordering per run is preserved.

### Where the relay code lives
The layer rules apply. The scheduled trigger and the Kafka publisher live in `stream`. Reading and marking outbox rows is a `service` method that uses a repository. `stream` calls that service method and passes the publisher.

## Delivery guarantee
At least once. If the relay publishes and then crashes before setting `sent_at`, the next run publishes the row again. Consumers must drop duplicates by `eventId`; every event carries it.

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
- Two events have two different `eventId` values.

## Not decided yet
- Deleting old sent rows. Until then the table grows; fine for the lab.
