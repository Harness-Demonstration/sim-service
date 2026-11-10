package com.harnesslab.sim.service;

import com.harnesslab.sim.types.OutboxEvent;
import java.util.concurrent.CompletableFuture;

public interface SimEventPublisher {
    /** Starts sending without waiting; the future completes when Kafka acknowledged the event, or fails. */
    CompletableFuture<Void> publish(OutboxEvent event);
}
