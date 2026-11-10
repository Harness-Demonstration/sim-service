# sim-service architecture

A map of the service, not a manual. Change it when a rule changes, not with every commit.

## What it does
`sim-service` owns the lifecycle of a SIM card. It consumes `sim.ordered`, stores the SIM, activates it on `POST /sims/{iccid}/activate`, and publishes `sim.activated`. Billing starts charging when it sees `sim.activated`.

## Codemap
All code is under `com.harnesslab.sim`. Dependencies flow one way, from top to bottom of this list.

| Package | Holds today | May depend on |
|---|---|---|
| `types` | `Sim`, `SimOrdered`, `SimActivated`: records, no logic | nothing |
| `config` | `ClockConfiguration` and other beans | `types` |
| `repository` | `SimRepository` (interface used by service), `PostgresSimRepository`, `SimEntity`, Spring Data interfaces | `types` |
| `service` | `SimActivationService`: every business decision; `SimEventPublisher` (interface) | `repository`, `types` |
| `stream` | `SimOrderedConsumer` (Kafka in), `KafkaSimEventPublisher` (Kafka out) | `service`, `types` |
| `api` | `SimActivationController`, `SimExceptionHandler` | `service`, `types` |

## Invariants
Each invariant names what enforces it. A rule without an enforcer is a wish; add the enforcer or remove the rule.

| Invariant | Enforced by |
|---|---|
| `stream` and `api` never call `repository` | `LayerRulesTest.stream_and_api_do_not_call_repository` |
| JPA types (`jakarta.persistence`) stay inside `repository`; entities never leave it | `LayerRulesTest.persistence_annotations_stay_in_repository` |
| No `JdbcTemplate`; hand-written SQL only in Flyway migrations | `LayerRulesTest.jdbc_template_is_not_used` |
| Hibernate never creates tables; Flyway does | `spring.jpa.hibernate.ddl-auto=validate` |
| Time comes from the injected `java.time.Clock` | review, test `a1_` (fixed clock) |
| A state change and its event are committed together through the outbox | design doc [outbox](docs/design-docs/outbox.md); tests `a4_` to `a7_` once implemented |

## Absent on purpose
- No business logic in `stream` or `api`. If a consumer or controller needs an `if` about the domain, it belongs in `service`.
- No direct Kafka calls from `service`. Events leave through the outbox.
- No calls to other services' code or databases. Other services see this one only through Kafka topics.

## Related
- Criteria and their IDs: [docs/product-specs/sim-activation.md](docs/product-specs/sim-activation.md)
- How events are published: [docs/design-docs/outbox.md](docs/design-docs/outbox.md)
- How tests are written and named: [docs/testing.md](docs/testing.md)
