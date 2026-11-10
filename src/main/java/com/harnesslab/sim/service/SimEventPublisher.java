package com.harnesslab.sim.service;

import com.harnesslab.sim.types.OutboxEvent;

public interface SimEventPublisher {
    /** Returns only once Kafka acknowledged the event; throws if it did not. */
    void publish(OutboxEvent event);
}
