# Tracing across the outbox and Kafka

## Problem
Spring Kafka adds a W3C `traceparent` header when a message is sent inside a span, and continues the trace when a listener receives it. But events leave through the outbox: the REST activation only writes a row, and the relay sends it later from a scheduled job, outside the activation's trace. Without help, every Kafka message would start a new trace.

## Decision
1. Micrometer Tracing with the OpenTelemetry bridge, exported over OTLP to Jaeger (`management.otlp.tracing.endpoint`, local Jaeger from `infra/docker-compose.yml`). Every request is sampled in this lab.
2. `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` are on, so Spring Kafka writes and reads `traceparent`.
3. When an outbox row is written, `PostgresOutboxRepository` stores the current span as `traceparent` in `outbox.trace_parent` (migration V5).
4. `KafkaSimEventPublisher` opens a span `outbox relay <topic>` whose parent is that stored context, and sends inside it. The Kafka producer span and the header therefore belong to the original trace.
5. Observations of `@Scheduled` runs are switched off (`ObservationConfiguration`); the relay runs every second and would fill Jaeger with empty traces.

Proven by `TracingKafkaTest`: the `traceparent` on `sim.activated` has the trace id of the REST request span, and the producer span's parent is the relay span.

## Sources
- Debezium outbox event router: a `tracingspancontext` column carries the span context from the service that writes the outbox row to the component that publishes it.
- Spring for Apache Kafka reference, "Monitoring": observation on `KafkaTemplate` and listener containers, propagation through Micrometer Tracing.
- Spring Boot reference, "Tracing": Micrometer Tracing with the OpenTelemetry bridge and the OTLP exporter; `@AutoConfigureObservability` turns tracing on in tests.
