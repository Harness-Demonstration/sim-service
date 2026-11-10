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
| Kafka integration | real Kafka from Testcontainers | the relay and consumers |
| Architecture | ArchUnit in `LayerRulesTest` | every build |

## Testcontainers
- The version comes from the `testcontainers.version` property in `pom.xml` (1.21.4 or later). Never write a version on the individual dependencies; older versions cannot talk to the Docker Engine on GitHub runners.
- Images are pinned and match `infra/docker-compose.yml`: `postgres:16.4`, `apache/kafka:3.8.0`.
- Tests need Docker. `./mvnw verify` starts the containers itself.

## Test data
Synthetic values only: ICCIDs like `8949000000000000001`, MSISDNs like `491700000001`. Never real numbers.

## Deleting a test
Only when the criterion it proves is retired. Move the ID to "Retired criteria" in the spec with its replacement, and say so in the pull request. Deleting a test to make the build green is never allowed.
