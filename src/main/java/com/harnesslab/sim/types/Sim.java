package com.harnesslab.sim.types;

import java.time.Instant;

public record Sim(String iccid, String msisdn, Instant activatedAt, Instant deactivatedAt) {

    public Sim(String iccid, String msisdn, Instant activatedAt) {
        this(iccid, msisdn, activatedAt, null);
    }
}
