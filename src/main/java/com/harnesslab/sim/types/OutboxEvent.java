package com.harnesslab.sim.types;

import java.time.Instant;
import java.util.UUID;

/** An event waiting in, or already sent from, the outbox. sentAt is null until Kafka acknowledged it. */
public record OutboxEvent(
        UUID id,
        String aggregateId,
        String topic,
        String payload,
        Instant createdAt,
        Instant sentAt,
        long aggregateSequence) {

    public OutboxEvent(
            UUID id, String aggregateId, String topic, String payload, Instant createdAt, Instant sentAt) {
        this(id, aggregateId, topic, payload, createdAt, sentAt, 1);
    }
}
