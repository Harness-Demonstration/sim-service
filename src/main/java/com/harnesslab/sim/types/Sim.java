package com.harnesslab.sim.types;

import java.time.Instant;

public record Sim(String iccid, String msisdn, Instant activatedAt) {
}
