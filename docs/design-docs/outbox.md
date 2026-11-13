# Publishing events: the transactional outbox, read by Debezium

Status: accepted, 2026-11-13. Applies to every event this service publishes. Replaces the polling relay of 2026-11-10.

## Problem
A state change in PostgreSQL and the Kafka event that announces it cannot be written in one transaction. Whichever is written second can be lost:
- Save first, then publish: the publish fails, the SIM is active, billing never hears about it.
- Publish first, then save: the save fails, billing charges, this service does not see the SIM as active, and nothing repairs it.

## Rule
This is the Transactional outbox pattern with transaction log tailing, as described on microservices.io and in the Debezium outbox event router documentation.

The state change and an outbox row are written in the same database transaction. The service never talks to Kafka for events. Debezium, a Kafka Connect connector, reads committed changes from PostgreSQL's write-ahead log (WAL, the log PostgreSQL writes every change to before it changes a table) and publishes each outbox row to Kafka.

## Write path
1. A `service` method annotated `@Transactional` changes the state (for example sets `activated_at`) through its repository.
2. In the same method it saves one `OutboxEvent`. `PostgresOutboxRepository.save` inserts the row, flushes, and deletes the row again, all in that transaction. It refuses to run outside a transaction (`Propagation.MANDATORY`).
3. The transaction commits the state change, the insert and the delete together, or none of them.

The table is always empty. Debezium does not read the table; it reads the insert from the WAL and ignores the delete (Gunnar Morling, "Reliable Microservices Data Exchange With the Outbox Pattern", Debezium blog, 2019). No cleanup job is needed.

## Outbox table
Created by Flyway migrations V2 and V6.

| Column | Type | Meaning |
|---|---|---|
| `id` | `uuid` primary key | also the event's `eventId`; Debezium puts it in the Kafka header `id` |
| `aggregate_id` | `varchar` | the entity the event is about, used as the Kafka key (ICCID for SIM events) |
| `topic` | `varchar` | target topic, for example `sim.activated` |
| `payload` | `jsonb` | the event exactly as it will be published |
| `created_at` | `timestamptz` | from the injected `Clock` |
| `tracingspancontext` | `text`, nullable | the span that wrote the row, as `java.util.Properties` text; see `tracing.md` |

## Debezium
- Image: `infra/debezium/Dockerfile` (Debezium Kafka Connect 3.7.1.Final plus the OpenTelemetry Java agent).
- Connector configuration: `infra/debezium/sim-outbox.json`. Locally, `infra/docker-compose.yml` registers it; `DebeziumOutboxTest` registers the same file.
- PostgreSQL connector with the built-in `pgoutput` plugin. It reads through the replication slot `sim_outbox` and the publication `sim_outbox`, which lists only `public.outbox`; changes to other tables never leave the database. Migration V6 creates the publication, because this service decides what it publishes; the connector does not create publications. PostgreSQL needs `wal_level=logical`.
- The outbox event router turns each insert into one Kafka message: topic from `topic`, key from `aggregate_id`, value from `payload` as JSON (not a string), header `id` from `id`, and the trace from `tracingspancontext`.
- `heartbeat.interval.ms` is 10 seconds. The slot keeps all WAL that Debezium has not confirmed; when `sim` changes often but `outbox` rarely, heartbeats let Debezium confirm its position so PostgreSQL can delete old WAL (Debezium PostgreSQL connector documentation, "WAL disk space consumption").

## Ordering
PostgreSQL writes transactions to the WAL in commit order, and Debezium publishes them in that order. Every event of one SIM has the ICCID as key, so Kafka puts them on the same partition, and a consumer sees the activation before the deactivation. Events for different SIMs can land on different partitions and are not ordered against each other.

## Delivery guarantee
At least once. Debezium stores how far it has read (the offset) in Kafka. If Kafka Connect crashes after publishing but before storing the offset, the restarted connector publishes those events again (Debezium documentation: "Debezium, like Kafka, provides at least once delivery of change events"). Consumers must drop duplicates by `eventId`; every event carries it.

## Failure cases
| What fails | Result |
|---|---|
| Saving the state or inserting the outbox row | Transaction rolls back; no state change, nothing in the WAL, no event |
| Kafka unavailable | Activation still succeeds. Debezium publishes the events once Kafka is back |
| Debezium stopped | Events wait in the WAL; the connector continues where it stopped. The WAL grows on the database disk while it is stopped |
| Kafka Connect crashes after publishing, before storing its offset | Event published twice; consumer drops the duplicate by `eventId` |

## Operations
- Debezium creates the replication slot when the connector first runs. Events committed before that are never published, because their rows are already deleted. Deploy and start the connector before the first release that writes events; afterwards the slot keeps everything while the connector is down.
- Watch the replication slot: `pg_replication_slots.active` must be true, and the WAL kept for it must not grow without limit. A slot nobody reads can fill the database disk.
- Watch the connector: `GET /connectors/sim-outbox/status` on Kafka Connect must show the connector and its task as `RUNNING`.
- In production the database user, the publication and the password come from the platform (a dedicated replication user, a publication created by a migration or a DBA, secrets from Kafka Connect config providers), not from the JSON file in this repository.

## Tests expected for any new event
- The state change and the outbox insert reach the WAL together, and a failing outbox insert leaves no state change and nothing in the WAL (`OutboxPersistenceTest`, PostgreSQL's `test_decoding` plugin).
- Debezium publishes the event to Kafka with the right topic, key, payload and `eventId` (`DebeziumOutboxTest`).
- Two events have two different `eventId` values.
