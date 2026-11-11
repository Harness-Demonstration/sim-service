package com.harnesslab.sim.service;

public class SimNotActivatedException extends RuntimeException {
    public SimNotActivatedException(String iccid) {
        super("SIM was never activated: " + iccid);
    }
}
