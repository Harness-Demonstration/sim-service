package com.harnesslab.sim.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.SimOrderedDeadLetter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
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

    @Autowired
    public KafkaSimEventPublisher(
            KafkaTemplate<byte[], byte[]> kafkaTemplate, ObjectMapper objectMapper, ObjectProvider<Tracer> tracer) {
        this(kafkaTemplate, objectMapper, tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    public KafkaSimEventPublisher(KafkaTemplate<byte[], byte[]> kafkaTemplate, ObjectMapper objectMapper) {
        this(kafkaTemplate, objectMapper, Tracer.NOOP);
    }

    KafkaSimEventPublisher(KafkaTemplate<byte[], byte[]> kafkaTemplate, ObjectMapper objectMapper, Tracer tracer) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    /**
     * Sends inside a span whose parent is the span that wrote the outbox row, so the Kafka producer span and the
     * traceparent header Spring Kafka adds belong to the original trace (for example the REST activation).
     */
    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        return inTraceOf(event, () -> send(event));
    }

    private CompletableFuture<Void> inTraceOf(OutboxEvent event, Supplier<CompletableFuture<Void>> send) {
        String[] parts = event.traceParent() == null ? new String[0] : event.traceParent().split("-");
        if (parts.length != 4) {
            return send.get();
        }
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(parts[1]).spanId(parts[2]).sampled("01".equals(parts[3])).build();
        Span span = tracer.spanBuilder().setParent(parent).name("outbox relay " + event.topic()).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return send.get();
        } finally {
            span.end();
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
