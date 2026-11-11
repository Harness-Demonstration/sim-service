# SIM activation

Owner: SIM lifecycle team.

## Why
A customer buys a SIM. It must be activated before it works, and billing must start charging exactly when it is activated: never before, never twice, and never missed.

## Criteria
Behaviour only: what the service guarantees to customers and to other services. How it is built lives in `ARCHITECTURE.md` and `docs/design-docs/`.

IDs never change and are never reused. A new criterion takes the next free number. Each test is named after the ID it proves, for example `a2_activating_twice_publishes_once`.

| ID | Criterion | Status |
|---|---|---|
| A1 | Activating an ordered SIM publishes one `sim.activated` event with ICCID, MSISDN and the activation time from the injected Clock. | done |
| A2 | Activating the same SIM twice publishes once. | done |
| A3 | Activating an unknown ICCID returns 404 and publishes nothing. | done |
| A4 | An activation and its `sim.activated` event are either both recorded or neither is, even if the database or Kafka fails in the middle. | done |
| A5 | If Kafka is down, no activation event is lost; it is delivered once Kafka is back, without anyone calling `activate` again. | done |
| A6 | Two running instances of the service never publish the same event at the same time. | done |
| A7 | Every `sim.activated` event carries an `eventId` that tells it apart from a duplicate of itself, so billing can ignore duplicates. | done |
| A8 | Deactivating an active SIM publishes one `sim.deactivated` event with `eventId`, ICCID, MSISDN and the deactivation time from the injected Clock, so billing can stop charging. | done |
| A9 | Deactivating the same SIM twice publishes once and answers like the first call. | done |
| A10 | Deactivating a SIM that was never activated returns 409 and publishes nothing; an unknown ICCID returns 404 and publishes nothing. | done |
| A11 | A deactivation and its `sim.deactivated` event are either both recorded or neither is. | done |
| A12 | An ordered message with a missing or malformed ICCID or MSISDN, or unreadable JSON, is not registered and is delivered to `sim.ordered.dlt` unchanged with a rejection reason; subsequent valid orders are registered. | done |


## Retired criteria
| ID | Was | Replaced by |
|---|---|---|
| (none yet) | | |

## Decision log
- 2026-11-10: Events are published through a transactional outbox, see [outbox](../design-docs/outbox.md).
