package com.harnesslab.sim.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.SimOrderedDeadLetter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSimEventPublisher implements SimEventPublisher {

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public KafkaSimEventPublisher(
            KafkaTemplate<byte[], byte[]> kafkaTemplate,
            ObjectMapper objectMapper,
            Tracer tracer,
            ObjectProvider<Propagator> propagator) {
        this(kafkaTemplate, objectMapper, tracer, propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    public KafkaSimEventPublisher(KafkaTemplate<byte[], byte[]> kafkaTemplate, ObjectMapper objectMapper) {
        this(kafkaTemplate, objectMapper, Tracer.NOOP, Propagator.NOOP);
    }

    private KafkaSimEventPublisher(
            KafkaTemplate<byte[], byte[]> kafkaTemplate, ObjectMapper objectMapper, Tracer tracer, Propagator propagator) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Sends inside a span whose parent is the span that wrote the outbox row, so the Kafka producer span and the
     * traceparent header Spring Kafka adds belong to the original trace (for example the REST activation).
     */
    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        Map<String, String> headers = traceHeaders(event);
        if (headers.isEmpty()) {
            return send(event);
        }
        Span span = propagator.extract(headers, Map::get).name("outbox relay " + event.topic()).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return send(event);
        } finally {
            span.end();
        }
    }

    private Map<String, String> traceHeaders(OutboxEvent event) {
        if (event.traceContext() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(event.traceContext(), new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private CompletableFuture<Void> send(OutboxEvent event) {
        try {
            if (SimOrderedDeadLetter.TOPIC.equals(event.topic())) {
                SimOrderedDeadLetter deadLetter = objectMapper.readValue(event.payload(), SimOrderedDeadLetter.class);
                ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                        event.topic(), decode(deadLetter.key()), decode(deadLetter.value()));
                record.headers().add("reason", deadLetter.reason().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8));
                return kafkaTemplate.send(record).thenApply(result -> null);
            }
            return kafkaTemplate.send(event.topic(), event.aggregateId().getBytes(StandardCharsets.UTF_8),
                    event.payload().getBytes(StandardCharsets.UTF_8)).thenApply(result -> null);
        } catch (RuntimeException | JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static byte[] decode(String value) {
        return value == null ? null : Base64.getDecoder().decode(value);
    }
}
