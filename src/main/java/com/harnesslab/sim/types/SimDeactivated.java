package com.harnesslab.sim.types;

import java.time.Instant;
import java.util.UUID;

/** Message on sim.deactivated. A SIM has been deactivated and must no longer be billed. eventId identifies duplicates. */
public record SimDeactivated(UUID eventId, String iccid, String msisdn, Instant deactivatedAt) {
}
