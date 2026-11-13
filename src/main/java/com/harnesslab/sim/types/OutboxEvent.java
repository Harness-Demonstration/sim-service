package com.harnesslab.sim.types;

import java.time.Instant;
import java.util.UUID;

/**
 * An event written to the outbox. Debezium publishes it to {@code topic} with {@code aggregateId} as the Kafka key
 * and {@code payload} as the value. {@code id} is also the event's eventId.
 */
public record OutboxEvent(UUID id, String aggregateId, String topic, String payload, Instant createdAt) {
}
