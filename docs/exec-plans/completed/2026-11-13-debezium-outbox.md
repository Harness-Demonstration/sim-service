# Publish the outbox with Debezium instead of a polling relay

## Purpose / Big Picture
sim-service published its events with a relay that read the outbox table every second. After this change, Debezium reads the outbox inserts from PostgreSQL's write-ahead log (WAL, the log PostgreSQL writes every change to first) and publishes them to Kafka. Nothing of the relay remains. Anyone can see it work by starting `infra/docker-compose.yml`, activating a SIM and reading `sim.activated`; Jaeger shows Debezium's spans between the REST request and billing.

## Progress
- [x] Debezium image with the OpenTelemetry Java agent, connector configuration and compose stack in `infra/`.
- [x] Migration V6: relay columns and indexes dropped, `tracingspancontext` added.
- [x] `PostgresOutboxRepository` inserts and deletes the row in the caller's transaction and stores the span context.
- [x] Relay code, publisher interface, scheduling and its tests removed.
- [x] Rejected sim.ordered messages go to `sim.ordered.dlt` through Spring Kafka's `DeadLetterPublishingRecoverer`.
- [x] `OutboxPersistenceTest` reads the WAL with `test_decoding`; `DebeziumOutboxTest` runs the real connector.
- [x] Design docs, architecture, spec (A6 retired), testing guide, tech debt, contract text.

## Surprises & Discoveries
- PostgreSQL 16 does not send generated columns through logical replication, so a generated column cannot feed Debezium.
- Debezium 3.7.1 reads the span context only as `java.util.Properties` text. JSON support exists on Debezium's main branch, not in a release yet.
- Without a span context in the row, Debezium writes no `traceparent` header at all.
- With `publication.autocreate.mode=filtered` the connector fails when it starts before the outbox table exists ("No table filters found for filtered publication").
- Mockito's `doThrow` on a `@SpyBean` of a `@Transactional(MANDATORY)` repository calls the real method outside a transaction; the failure tests now make PostgreSQL reject the insert with a `CHECK (false)` constraint instead.

## Decision Log
- Only Debezium publishes; no switch back to the relay. Two publishing paths would need two sets of tests and runbooks.
- Dead letters leave the outbox. Debezium cannot reproduce the original bytes of a message that is not JSON; Spring Kafka's dead-letter publisher sends the original key and value unchanged.
- Rows are deleted in the same transaction, as Debezium's outbox article recommends, so the table needs no cleanup.
- Migration V6 creates the publication and the connector never creates one (`publication.autocreate.mode=disabled`): the service decides which tables leave the database.
- The agent is baked into a local image with a pinned version and SHA-256. On Kubernetes, Strimzi or the OpenTelemetry Operator adds it instead.

## Outcomes & Retrospective
All tests pass, including mutation checks: removing the delete fails `OutboxPersistenceTest` and `DebeziumOutboxTest.a1_...`; storing no span context fails the tracing test. Criterion A6 is retired because service instances no longer publish.

## Context and Orientation
Read `docs/design-docs/outbox.md` and `docs/design-docs/tracing.md`. The service writes outbox rows in `SimActivationService` through `OutboxRepository`. Debezium runs in Kafka Connect, outside this service.

## Plan of Work
Done in one pull request: infrastructure, migration, repository, removal of the relay, dead-letter handling, tests, documents.

## Concrete Steps
    docker compose -f infra/docker-compose.yml up -d --build
    ./mvnw spring-boot:run
    curl -X POST localhost:8080/sims/<iccid>/activate   # after the SIM was ordered on sim.ordered

## Validation and Acceptance
`./mvnw verify` passes. `DebeziumOutboxTest` proves publishing, ordering on one partition, delivery after a Kafka outage, and one trace across the service and Debezium.

## Idempotence and Recovery
The compose stack can be started again at any time; `connect-setup` updates the connector with PUT. `docker compose down -v` resets all data.

## Artifacts and Notes
Measured locally: commit to Debezium reading the change about 0.4 s, Debezium processing about 50 ms.

## Interfaces and Dependencies
Debezium Kafka Connect 3.7.1.Final, OpenTelemetry Java agent 2.33.1, PostgreSQL 16.4 with `wal_level=logical`, Kafka 3.8.0.
