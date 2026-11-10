package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.harnesslab.sim.repository.InMemorySimRepository;
import com.harnesslab.sim.types.SimActivated;
import com.harnesslab.sim.types.SimOrdered;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimActivationServiceTest {

    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";
    private static final Instant ACTIVATION_TIME = Instant.parse("2026-01-02T03:04:05Z");

    private final List<SimActivated> events = new ArrayList<>();
    private SimActivationService service;

    @BeforeEach
    void setUp() {
        service = new SimActivationService(
                new InMemorySimRepository(),
                events::add,
                Clock.fixed(ACTIVATION_TIME, ZoneOffset.UTC));
        service.registerOrdered(new SimOrdered(ICCID, MSISDN, "order-1"));
    }

    @Test
    void c1_activating_an_ordered_sim_publishes_iccid_msisdn_and_activation_time() {
        service.activate(ICCID);

        assertEquals(List.of(new SimActivated(ICCID, MSISDN, ACTIVATION_TIME)), events);
    }

    @Test
    void c2_activating_twice_publishes_once() {
        service.activate(ICCID);
        service.activate(ICCID);

        assertEquals(List.of(new SimActivated(ICCID, MSISDN, ACTIVATION_TIME)), events);
    }
}
