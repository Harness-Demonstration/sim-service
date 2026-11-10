# sim-service

Consumes `sim.ordered`, activates SIMs, produces `sim.activated`. Part of the harness lab.

Build: `./mvnw -q verify`

For local development, start PostgreSQL with `docker compose -f infra/docker-compose.yml up -d`.
The service connects to `jdbc:postgresql://localhost:5432/sim` with user/password `sim`.
Override the connection with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD`.
