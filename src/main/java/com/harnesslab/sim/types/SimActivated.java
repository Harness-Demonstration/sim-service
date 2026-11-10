package com.harnesslab.sim.types;

import java.time.Instant;
import java.util.UUID;

/** Message on sim.activated. A SIM has been activated and can be billed. eventId identifies duplicates. */
public record SimActivated(UUID eventId, String iccid, String msisdn, Instant activatedAt) {
}
