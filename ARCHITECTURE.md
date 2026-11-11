# Architecture

This document describes the high-level architecture of sim-service. If you want to know where something is or what must stay true, start here. It is revisited a few times a year, not on every change; names below are for symbol search, not links.

## Bird's Eye View

sim-service owns the lifecycle of a SIM card. A sold SIM arrives as a `sim.ordered` Kafka message and is stored in PostgreSQL. A client activates it over REST. The activation is announced on the `sim.activated` topic, which billing uses to start charging.

The one hard problem is that announcement: billing must never miss an activation and must never be charged for one this service does not know about. PostgreSQL and Kafka cannot share a transaction, so events leave through a transactional outbox (see `docs/design-docs/outbox.md`).

## Entry Points

- `SimOrderedConsumer`: Kafka listener for `sim.ordered`.
- `SimActivationController`: `POST /sims/{iccid}/activate`.
- `OutboxRelay`: scheduled job that publishes pending outbox rows to Kafka.

## Code Map

All code lives under `com.harnesslab.sim`. Dependencies point down this list: `api` and `stream` use `service`, `service` uses `repository`, everything may use `types`. Enforced by `LayerRulesTest.layers_depend_in_one_direction`.

### `types`

Plain records shared by all layers: `Sim`, the incoming `SimOrdered`, the outgoing `SimActivated`, and `OutboxEvent`. No logic, no Spring, no JPA.

### `config`

Spring beans: the `Clock` used everywhere instead of `Instant.now()`, and scheduling for the relay.

**Architecture Invariant:** no code calls `Instant.now()`; time comes from the injected `Clock`. Enforced by `LayerRulesTest.no_wall_clock`.

### `repository`

Persistence behind interfaces that `service` uses: `SimRepository` and `OutboxRepository`. Implemented with Spring Data JPA; `SimEntity` and `OutboxEntity` map to tables created by Flyway migrations in `src/main/resources/db/migration`.

**Architecture Invariant:** JPA types (`jakarta.persistence`) never leave this package; callers get `types` records. Enforced by `LayerRulesTest`.

**Architecture Invariant:** no hand-written SQL in Java and no `JdbcTemplate`; SQL lives only in migrations. Hibernate runs with `ddl-auto=validate` and never creates tables.

### `service`

Every business decision. `SimActivationService` registers ordered SIMs and activates them: it locks the SIM row, sets the activation time and writes the outbox row in one transaction. `OutboxRelayService` selects pending outbox rows, hands them to a publisher and marks the acknowledged ones as sent.

**Architecture Invariant:** `service` never talks to Kafka. It sees only the `SimEventPublisher` interface, implemented in `stream`. Enforced by `LayerRulesTest.service_does_not_use_kafka`.

### `stream`

Kafka in both directions. `SimOrderedConsumer` turns a message into a `SimOrdered` record and calls one service method. `KafkaSimEventPublisher` sends outbox payloads and returns Kafka's acknowledgement. `OutboxRelay` triggers the relay every second.

**Architecture Invariant:** no business logic here, and no access to `repository`. Enforced by `LayerRulesTest`.

### `api`

REST. `SimActivationController` translates the request and calls the service; `SimExceptionHandler` turns `SimNotFoundException` into 404.

**Architecture Invariant:** same as `stream`: no business logic, no `repository`.

## Cross-Cutting Concerns

### Events and delivery

Every event goes through the outbox. Delivery is at least once; every `sim.activated` carries an `eventId` so consumers can drop duplicates. Changing the shape of a published message affects other services and must be called out in the pull request.

### Testing

Business rules are tested in `service` with in-class fakes and a fixed `Clock`. Anything that touches SQL, transactions or Kafka runs against real PostgreSQL and Kafka from Testcontainers. Naming and versions: `docs/testing.md`. The knowledge base itself is checked by `scripts/check-docs.sh` in CI.

### Error handling

Unknown SIMs raise `SimNotFoundException`, mapped to 404. A failed Kafka send never fails an activation; the outbox row stays pending and the relay retries it.

### Observability

Logs only, for now. Tracing across Kafka is planned.

### Configuration

Database and Kafka addresses come from environment variables with local defaults (`application.yml`). The relay can be switched off with `sim.outbox.relay.enabled=false`. Local PostgreSQL, Kafka and Jaeger start from `infra/docker-compose.yml`.
