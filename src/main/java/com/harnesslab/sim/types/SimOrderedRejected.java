package com.harnesslab.sim.types;

/** Message on sim.ordered.dead-letter. The original sim.ordered message, unchanged, and why it was rejected. */
public record SimOrderedRejected(String reason, String payload) {
}
