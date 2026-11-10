# SIM activation

Owner: SIM lifecycle team.

## Why
A customer buys a SIM. It must be activated before it works, and billing must start charging exactly when it is activated: never before, never twice, and never missed.

## Criteria
IDs never change and are never reused. A new criterion takes the next free number. Each test is named after the ID it proves, for example `a2_activating_twice_publishes_once`.

### Activation
| ID | Criterion | Status |
|---|---|---|
| A1 | Activating an ordered SIM publishes one `sim.activated` event with ICCID, MSISDN and the activation time from the injected Clock. | done |
| A2 | Activating the same SIM twice publishes once. | done |
| A3 | Activating an unknown ICCID returns 404 and publishes nothing. | done |
| A4 | Activation writes the SIM as active and one outbox row in one transaction. If either write fails, neither is committed. | planned |
| A5 | The relay publishes pending outbox rows in creation order and marks a row sent only after Kafka acknowledged it. | planned |
| A6 | While Kafka is unavailable, rows stay pending and nothing is lost; they are published when Kafka is back. | planned |
| A7 | Every `sim.activated` event carries a unique `eventId`. Delivery is at least once, so consumers drop duplicates by `eventId`. | planned |

### Persistence
| ID | Criterion | Status |
|---|---|---|
| P1 | The `sim` table has `iccid` as primary key and `msisdn` not null. | done |
| P2 | SIMs are saved and read back unchanged against a real PostgreSQL. | done |

## Retired criteria
| ID | Was | Replaced by |
|---|---|---|
| (none yet) | | |

## Decision log
- 2026-11-10: Events are published through a transactional outbox, see [outbox](../design-docs/outbox.md). The earlier behaviour "a failed publish makes activation fail" is replaced by A6.
