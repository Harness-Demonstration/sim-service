# Tech debt tracker

Known gaps we chose not to fix yet. Each line says why it matters and what would fix it. Remove a line in the pull request that fixes it.

| Gap | Why it matters | Likely fix |
|---|---|---|
| Outbox rows that fail forever are retried every second with no limit | 100 permanently failing rows fill every batch and newer events are never sent | Attempt counter, growing delay, a failed state after N attempts |
| No metric for pending outbox rows or the age of the oldest one | A stuck relay is noticed only when billing complains | Publish both as metrics and alert on them |
| The relay keeps its transaction open while waiting for Kafka acknowledgements (up to 10 seconds) | A slow Kafka holds a pooled connection and row locks | Claim rows with a lease, publish outside a transaction, mark sent in a second short transaction |
