package com.harnesslab.sim.types;

import java.time.Instant;

public record SimActivated(String iccid, String msisdn, Instant activatedAt) {
}
