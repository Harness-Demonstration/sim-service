# Tech debt tracker

Known gaps we chose not to fix yet. Each line says why it matters and what would fix it. Remove a line in the pull request that fixes it.

| Gap | Why it matters | Likely fix |
|---|---|---|
| No alert on the Debezium connector state or on the WAL kept by the `sim_outbox` replication slot | A stopped connector is noticed only when billing complains, and the WAL it keeps can fill the database disk | Alert when the connector or its task is not `RUNNING` and when the slot keeps more WAL than a set limit |
