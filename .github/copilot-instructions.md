# sim-service: the map

Read this first. It stays under 100 lines and tells you where to look next. Open only the documents your task needs.

## What this service does
Consumes `sim.ordered`, stores the SIM in PostgreSQL, activates it on `POST /sims/{iccid}/activate`, publishes `sim.activated`.
Billing starts charging when it sees `sim.activated`, so that event must never be lost and consumers must be able to drop duplicates.

Stack: Java 17, Spring Boot 3.3, Spring Data JPA, Flyway, PostgreSQL 16, Spring Kafka with Kafka 3.8, Maven wrapper.

## Where to look
| You need | Read |
|---|---|
| Packages, what may depend on what, invariants | `ARCHITECTURE.md` |
| How we work with agents | `docs/design-docs/core-beliefs.md` |
| Lasting behaviour the service guarantees, with IDs (A1, A2 ...) | `docs/product-specs/index.md` |
| Design decisions, for example how events are published | `docs/design-docs/index.md` |
| How to name and write tests, Testcontainers versions | `docs/testing.md` |
| Kafka message contract | `docs/contracts/sim-events.asyncapi.yaml` |
| Generated Kafka topic documentation | `docs/generated/kafka-topics.md` |
| Planning a complex feature or a significant refactor | `docs/PLANS.md`, plans in `docs/exec-plans/` |
| Known gaps we chose not to fix yet | `docs/exec-plans/tech-debt-tracker.md` |
| Local PostgreSQL, Kafka and Jaeger | `infra/docker-compose.yml` |

## How to work
1. If the issue adds or changes lasting behaviour that customers or other services rely on, write it into the matching spec in `docs/product-specs/` first, with the next free ID, and retire criteria that no longer hold. Technical decisions go to `ARCHITECTURE.md` or `docs/design-docs/`. All other acceptance criteria stay in the issue and are proven by tests.
2. Write the test first. A test that proves a spec criterion starts with its ID, for example `a2_activating_twice_publishes_once`; other tests are named after the behaviour. Details in `docs/testing.md`.
3. Put every decision in `service`. Controllers and consumers translate input and call one service method.
4. Publish events only through the outbox, as described in `docs/design-docs/outbox.md`.
5. Persistence uses Spring Data JPA; entities stay in `repository` and map to `types` records; hand-written SQL only in Flyway migrations.
6. Take the time from the injected `java.time.Clock`.
7. Before you open or update the pull request, run `./mvnw -q verify` (needs Docker for Testcontainers) and `scripts/check-docs.sh`.
8. In the pull request description, list each acceptance criterion with the test that proves it.
9. If you add a document under `docs/`, link it from the matching `index.md` or from this map.
10. Change Kafka message shapes in `docs/contracts/sim-events.asyncapi.yaml` before changing Java code.

## Never
- Never delete or weaken a test to make the build green. A test is removed only when the behaviour it proves is intentionally changed, and the pull request says so.
- Never put real phone numbers or ICCIDs in tests.
- Never change the shape of a Kafka message without saying so in the pull request; other services read it.
- Never edit files in `docs/generated/` by hand; regenerate them with `scripts/check-generated-docs.sh --update`.
