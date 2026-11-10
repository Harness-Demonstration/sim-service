package com.harnesslab.sim.types;

import java.time.Instant;

/** Message on sim.activated. A SIM has been activated and can be billed. */
public record SimActivated(String iccid, String msisdn, Instant activatedAt) {
}
