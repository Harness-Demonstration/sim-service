# Testing in sim-service

## Naming
- A test that proves a spec criterion starts with the criterion's ID in lower case, followed by the behaviour: `a2_activating_twice_publishes_once`.
- Only lasting behaviour goes into the spec (see the map, How to work, item 1). Acceptance criteria of one-off tasks stay in the issue; their tests are named after the behaviour, without an ID.
- When a change makes an existing criterion wrong, retire it in the spec and delete or rewrite its test in the same pull request.
- All other tests (technical checks, wiring, smoke tests) are named after the behaviour and have no prefix, for example `saves_and_reads_back_activation_time`.

## Kinds of tests
| Kind | Uses | When |
|---|---|---|
| Unit | plain JUnit, fakes declared inside the test class, fixed `Clock` | business rules in `service` |
| Repository and outbox | real PostgreSQL from Testcontainers, Flyway migrations applied | anything that touches SQL or transactions |
| Outbox | real PostgreSQL with `wal_level=logical`, read through the `test_decoding` plugin | what a state change writes to the write-ahead log (`OutboxPersistenceTest`) |
| Kafka integration | real Kafka from Testcontainers | consumers and the dead-letter topic |
| Debezium end to end | PostgreSQL, Kafka and the image from `infra/debezium` on one Docker network, connector from `infra/debezium/sim-outbox.json` | publishing, ordering, Kafka outages and tracing (`DebeziumOutboxTest`) |
| Contract | JSON Schema from the AsyncAPI contract, `MessageContractTest` | every message shape the service sends or reads |
| Consumer contract | CI job `consumer-contracts` runs billing-service's `MessageContractTest` against this pull request's contract | a contract change that breaks billing-service |
| Architecture | ArchUnit in `LayerRulesTest` | every build |

## Testcontainers
- The version comes from the `testcontainers.version` property in `pom.xml` (1.21.4 or later). Never write a version on the individual dependencies; older versions cannot talk to the Docker Engine on GitHub runners.
- Images are pinned and match `infra/docker-compose.yml`: `postgres:16.4`, `apache/kafka:3.8.0`, and the Debezium image built from `infra/debezium/Dockerfile`.
- Tests need Docker. `./mvnw verify` starts the containers itself.

## Test data
Synthetic values only: ICCIDs like `8949000000000000001`, MSISDNs like `491700000001`. Never real numbers.

## Deleting a test
Only when the behaviour it proves is intentionally changed. If that behaviour is a spec criterion, retire it in the spec. Say so in the pull request. Deleting a test to make the build green is never allowed.
