package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.types.Sim;
import com.harnesslab.sim.types.SimActivated;
import com.harnesslab.sim.types.SimOrdered;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimActivationServiceTest {

    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";
    private static final Instant ACTIVATION_TIME = Instant.parse("2026-01-02T03:04:05Z");

    private final List<SimActivated> events = new ArrayList<>();
    private TestSimRepository repository;
    private SimActivationService service;

    @BeforeEach
    void setUp() {
        repository = new TestSimRepository();
        service = new SimActivationService(
                repository,
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

    @Test
    void c4_failed_publish_keeps_sim_inactive_and_allows_retry() {
        List<SimActivated> publishedEvents = new ArrayList<>();
        int[] publishAttempts = {0};
        SimActivationService retryableService = new SimActivationService(
                repository,
                event -> {
                    if (publishAttempts[0]++ == 0) {
                        throw new IllegalStateException("Kafka publish failed");
                    }
                    publishedEvents.add(event);
                },
                Clock.fixed(ACTIVATION_TIME, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> retryableService.activate(ICCID));
        assertNull(repository.findByIccid(ICCID).orElseThrow().activatedAt());

        retryableService.activate(ICCID);

        assertEquals(1, publishedEvents.size());
        assertEquals(ACTIVATION_TIME, repository.findByIccid(ICCID).orElseThrow().activatedAt());
    }

    private static class TestSimRepository implements SimRepository {

        private final Map<String, Sim> sims = new HashMap<>();

        @Override
        public Optional<Sim> findByIccid(String iccid) {
            return Optional.ofNullable(sims.get(iccid));
        }

        @Override
        public void save(Sim sim) {
            sims.put(sim.iccid(), sim);
        }
    }
}
