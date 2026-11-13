package com.harnesslab.sim.service;

/**
 * A sim.ordered message that cannot be registered. The Kafka listener's error handler sends the original record to
 * sim.ordered.dlt with this message as the {@code reason} header and does not retry it.
 */
public class InvalidSimOrderException extends RuntimeException {
    public InvalidSimOrderException(String reason) {
        super(reason);
    }
}
