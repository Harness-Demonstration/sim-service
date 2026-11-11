package com.harnesslab.sim.types;

/** JSON outbox representation of the original Kafka key and value, encoded as base64. */
public record SimOrderedDeadLetter(String key, String value, String reason) {
    public static final String TOPIC = "sim.ordered.dlt";
}
