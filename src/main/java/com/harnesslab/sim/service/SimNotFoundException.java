package com.harnesslab.sim.service;

public class SimNotFoundException extends RuntimeException {
    public SimNotFoundException(String iccid) {
        super("SIM not found: " + iccid);
    }
}
