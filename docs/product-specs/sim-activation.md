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
- 2026-11-10: Events are published through a transactional outbox, see [outbox](../design-docs/outbox.md).
