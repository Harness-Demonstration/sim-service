package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.OutboxEvent;

public interface OutboxRepository {
    /**
     * Writes the event to the outbox inside the caller's transaction. Debezium publishes it from the write-ahead
     * log after the transaction commits; nothing is published if it rolls back.
     */
    void save(OutboxEvent event);
}
