package com.harnesslab.sim.types;

import java.time.Instant;
import java.util.UUID;

/**
 * An event waiting in, or already sent from, the outbox. sentAt is null until Kafka acknowledged it.
 * traceContext holds the trace headers of the span that wrote the row as JSON, or null; the relay continues that trace.
 */
public record OutboxEvent(
        UUID id,
        String aggregateId,
        String topic,
        String payload,
        Instant createdAt,
        Instant sentAt,
        long aggregateSequence,
        String traceContext) {

    public OutboxEvent(
            UUID id,
            String aggregateId,
            String topic,
            String payload,
            Instant createdAt,
            Instant sentAt,
            long aggregateSequence) {
        this(id, aggregateId, topic, payload, createdAt, sentAt, aggregateSequence, null);
    }

    public OutboxEvent(
            UUID id, String aggregateId, String topic, String payload, Instant createdAt, Instant sentAt) {
        this(id, aggregateId, topic, payload, createdAt, sentAt, 1, null);
    }
}
