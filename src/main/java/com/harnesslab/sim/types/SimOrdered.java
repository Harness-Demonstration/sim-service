package com.harnesslab.sim.types;

/** Message on sim.ordered. A SIM card was sold and must be registered. */
public record SimOrdered(String iccid, String msisdn, String orderId) {
}
