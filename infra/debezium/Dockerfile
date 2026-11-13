# Kafka Connect with the Debezium connectors and the OpenTelemetry Java agent.
# On Kubernetes the agent is usually added by the OpenTelemetry Operator or Strimzi's tracing support instead;
# locally and in tests it is part of the image. See docs/design-docs/tracing.md.
FROM quay.io/debezium/connect:3.7.1.Final

ARG OTEL_AGENT_VERSION=2.33.1
# Digest published with the release on github.com/open-telemetry/opentelemetry-java-instrumentation.
ARG OTEL_AGENT_SHA256=e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149

RUN mkdir -p /kafka/otel-agent \
    && curl -fsSL -o /kafka/otel-agent/opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    && echo "${OTEL_AGENT_SHA256}  /kafka/otel-agent/opentelemetry-javaagent.jar" | sha256sum -c -

# ENABLE_OTEL puts the OpenTelemetry API jars shipped with the image on the classpath.
# Only the OpenTelemetry API bridge is instrumented: Debezium creates its spans through that API.
# Connect's own Kafka, JDBC and REST traffic would otherwise create many unrelated traces.
ENV ENABLE_OTEL=true \
    KAFKA_OPTS=-javaagent:/kafka/otel-agent/opentelemetry-javaagent.jar \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none \
    OTEL_PROPAGATORS=tracecontext \
    OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=false \
    OTEL_INSTRUMENTATION_OPENTELEMETRY_API_ENABLED=true
