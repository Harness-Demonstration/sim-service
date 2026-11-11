package com.harnesslab.sim.types;

import java.util.UUID;

/** Message on sim.ordered.dead-letter. The original sim.ordered message, unchanged, and why it was rejected. */
public record SimOrderedRejected(UUID eventId, String reason, String payload) {
}
