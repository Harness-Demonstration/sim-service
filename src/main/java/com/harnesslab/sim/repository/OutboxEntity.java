package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.OutboxEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "aggregate_sequence", nullable = false)
    private long aggregateSequence;

    @Column(nullable = false, length = 128)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEntity() {}

    OutboxEntity(OutboxEvent event) {
        this.id = event.id();
        this.aggregateId = event.aggregateId();
        this.aggregateSequence = event.aggregateSequence();
        this.topic = event.topic();
        this.payload = event.payload();
        this.createdAt = event.createdAt();
        this.sentAt = event.sentAt();
    }

    OutboxEvent toDomain() {
        return new OutboxEvent(id, aggregateId, topic, payload, createdAt, sentAt, aggregateSequence);
    }
}
