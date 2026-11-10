# sim-service: the map

Read this first. It stays under 100 lines and tells you where to look next. Open only the documents your task needs.

## What this service does
Consumes `sim.ordered`, stores the SIM in PostgreSQL, activates it on `POST /sims/{iccid}/activate`, publishes `sim.activated`.
Billing starts charging when it sees `sim.activated`, so that event must never be lost and consumers must be able to drop duplicates.

## Where to look
| You need | Read |
|---|---|
| Packages, what may call what, invariants and what enforces them | `ARCHITECTURE.md` |
| The criteria and their IDs (A1, A2, P1 ...); where new criteria go | `docs/product-specs/sim-activation.md` |
| How to publish any event (transactional outbox, relay, at-least-once) | `docs/design-docs/outbox.md` |
| How to name and write tests, Testcontainers versions | `docs/testing.md` |
| Local PostgreSQL, Kafka and Jaeger | `infra/docker-compose.yml` |

## How to work
1. Your first change is to the spec. Issues describe the task in plain words; turn that into criteria in the matching file in `docs/product-specs/`, each with the next free ID. If an existing criterion no longer holds, retire it there. The reviewer reads this spec diff before any code.
2. Write the test first and name it after the ID, for example `a2_activating_twice_publishes_once`. Details in `docs/testing.md`.
3. Put every decision in `service`. Controllers and consumers translate input and call one service method.
4. Publish events only through the outbox, as described in `docs/design-docs/outbox.md`. Never call Kafka from `service`.
5. Persistence uses Spring Data JPA; entities stay in `repository` and map to `types` records; hand-written SQL only in Flyway migrations.
6. Take the time from the injected `java.time.Clock`, never `Instant.now()`.
7. Run `./mvnw -q verify` before you open or update the pull request. It needs Docker for Testcontainers.
8. In the pull request description, list each criterion ID with the test that proves it.

## Never
- Never delete or weaken a test to make the build green. A test is removed only when its criterion is retired in the spec; see `docs/testing.md`.
- Never put real phone numbers or ICCIDs in tests.
- Never change the shape of a Kafka message without saying so in the PR; other services read it.
- Never write a criterion only in the issue or the PR. It belongs in the spec.
