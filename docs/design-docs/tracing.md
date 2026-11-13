# Tracing across the outbox and Kafka

## Problem
Spring Kafka continues a trace when a listener receives a message with a W3C `traceparent` header. But this service does not send its events to Kafka: it writes an outbox row, and Debezium publishes it later from another process (see `outbox.md`). Without help, every Kafka message would start a new trace, and the time between the commit and the Kafka message would be invisible.

## Decision
We use Debezium's own tracing support, as described in the Debezium documentation, "Distributed tracing".

1. Micrometer Tracing with the OpenTelemetry bridge, exported over OTLP (the OpenTelemetry protocol) to Jaeger (`management.otlp.tracing.endpoint`). Every request is sampled in this lab.
2. When an outbox row is written, `PostgresOutboxRepository` asks Micrometer's `Propagator` to inject the current span into a `java.util.Properties` and stores it as text in `outbox.tracingspancontext`, for example `traceparent=00-<trace id>-<span id>-01`. Debezium 3.7 reads this column with `Properties.load`; the column name is Debezium's default.
3. Kafka Connect runs with the OpenTelemetry Java agent (`infra/debezium/Dockerfile`). The agent provides the OpenTelemetry SDK that Debezium's spans need and exports them to Jaeger under the service name `debezium`. All other instrumentation of the agent is switched off, so Connect's own Kafka, database and REST traffic creates no traces.
4. For each outbox row, Debezium's outbox event router continues the trace from that column and creates two spans:
   - `db-log-write`: starts at the commit time in PostgreSQL (taken from the WAL record), ends when Debezium has processed the change.
   - `debezium-read`: starts when Debezium read the change from the WAL.

   It then writes its own span as `traceparent` into the Kafka message headers.
5. billing-service's Spring Kafka listener reads `traceparent` and continues the same trace.

One trace in Jaeger therefore shows: `sim-service` REST request, `debezium` `db-log-write` and `debezium-read`, then billing-service receiving `sim.activated`.

Proven by `DebeziumOutboxTest.rest_activation_debezium_and_the_kafka_message_form_one_trace`: the `traceparent` on `sim.activated` has the trace id of the REST request span, and its parent span is not one of this service's spans but Debezium's. Without a span context in the row, Debezium writes no `traceparent` at all.

## In production
The agent is not baked into an image by hand. Two standard ways on Kubernetes:
- Strimzi: `spec.tracing.type: opentelemetry` on the `KafkaConnect` resource, with `OTEL_SERVICE_NAME` and `OTEL_EXPORTER_OTLP_ENDPOINT` in `spec.template.connectContainer.env` (Strimzi documentation, "Introducing distributed tracing").
- OpenTelemetry Operator: an `Instrumentation` resource and the pod annotation `instrumentation.opentelemetry.io/inject-java: "true"`; the operator adds the agent through an init container (OpenTelemetry documentation, "Injecting auto-instrumentation").

The service side (the `tracingspancontext` column) and the connector configuration stay the same.

## Sources
- Debezium documentation, "Distributed tracing" and "Outbox event router": `tracing.span.context.field`, the `java.util.Properties` format, the `db-log-write` and `debezium-read` spans, `ENABLE_OTEL` in the container image.
- Debezium source code, `io.debezium.transforms.tracing.TracingSpanUtil` (3.7.1.Final): span names and start times.
- Spring for Apache Kafka reference, "Monitoring": observation on listener containers, propagation through Micrometer Tracing.
- Spring Boot reference, "Tracing": Micrometer Tracing with the OpenTelemetry bridge; `@AutoConfigureObservability` turns tracing on in tests.
