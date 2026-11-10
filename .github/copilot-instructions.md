# sim-service: the map

Read this first. Keep it under 100 lines. It says where to look, not everything.

## What this service does
Consumes `sim.ordered`, stores the SIM, activates it on request, publishes `sim.activated`.
Billing starts charging when it sees `sim.activated`, so that event must be published exactly once per SIM.

## Packages and the one rule between them
Code lives in `com.harnesslab.sim`, in six packages. Dependencies flow one way.

| Package | Holds | May call |
|---|---|---|
| `types` | records and enums, no logic | nothing |
| `config` | Spring configuration and beans | types |
| `repository` | persistence | types |
| `service` | business rules; the only place that decides anything | repository, types |
| `stream` | Kafka consumers: deserialise, log one line, call one service method | service, types |
| `api` | REST controllers: parse the request, call one service method | service, types |

`stream` and `api` never call `repository`. `LayerRulesTest` enforces this and its failure message says how to fix it.

## How to work
1. Turn each acceptance criterion in the issue into a test first. Name the test after the criterion, for example `c2_activating_twice_publishes_once`.
2. Put the decision in `service`. Controllers and consumers only translate input and call the service.
3. A state change and the event it causes are written in one database transaction: update the row and insert into the `outbox` table, then commit. A separate relay publishes outbox rows to Kafka and retries until it succeeds. Never send to Kafka from `service`, and never save state first and publish afterwards: Kafka and the database cannot share a transaction, so one of the two writes can be lost.
4. Take the current time from an injected `java.time.Clock` bean defined in `config`, never `Instant.now()`, so tests can fix the time.
5. Run `./mvnw -q verify` before you open or update the pull request. It needs no Kafka broker.
6. In the pull request description, list each criterion with the test that proves it.

## Never
- Never delete or weaken a test to make the build green. Say so in the PR instead.
- Never put real phone numbers or ICCIDs in tests. Use values like `8949000000000000001` and `491700000001`.
- Never change the shape of a Kafka message without saying so in the PR; other services read it.

## Build
`./mvnw -q verify` runs all tests in under a minute.
