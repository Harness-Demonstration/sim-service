package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.types.OutboxEvent;
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
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Clock clock = Clock.fixed(ACTIVATION_TIME, ZoneOffset.UTC);
    private TestSimRepository repository;
    private TestOutboxRepository outbox;
    private SimActivationService service;

    @BeforeEach
    void setUp() {
        repository = new TestSimRepository();
        outbox = new TestOutboxRepository();
        service = new SimActivationService(repository, outbox, MAPPER, clock);
        service.registerOrdered(new SimOrdered(ICCID, MSISDN, "order-1"));
    }

    @Test
    void a1_activating_an_ordered_sim_publishes_iccid_msisdn_and_activation_time() throws Exception {
        service.activate(ICCID);

        assertEquals(1, outbox.events.size());
        OutboxEvent row = outbox.events.get(0);
        assertEquals("sim.activated", row.topic());
        assertEquals(ICCID, row.aggregateId());
        SimActivated event = MAPPER.readValue(row.payload(), SimActivated.class);
        assertEquals(new SimActivated(row.id(), ICCID, MSISDN, ACTIVATION_TIME), event);
        assertEquals(ACTIVATION_TIME, repository.findByIccid(ICCID).orElseThrow().activatedAt());
    }

    @Test
    void a2_activating_twice_publishes_once() {
        service.activate(ICCID);
        service.activate(ICCID);

        assertEquals(1, outbox.events.size());
    }

    @Test
    void a7_two_events_have_different_event_ids() {
        service.registerOrdered(new SimOrdered("8949000000000000002", "491700000002", "order-2"));

        service.activate(ICCID);
        service.activate("8949000000000000002");

        assertNotEquals(outbox.events.get(0).id(), outbox.events.get(1).id());
    }

    @Test
    void a5_failed_publish_keeps_event_pending_and_a_later_run_sends_it() {
        service.activate(ICCID);
        OutboxRelayService relay = new OutboxRelayService(outbox, clock);
        List<OutboxEvent> published = new ArrayList<>();
        int[] attempts = {0};

        relay.relayPending(event -> {
            if (attempts[0]++ == 0) {
                throw new IllegalStateException("Kafka down");
            }
            published.add(event);
        });
        assertNull(outbox.events.get(0).sentAt());
        assertEquals(List.of(), published);

        assertEquals(1, relay.relayPending(published::add));
        assertEquals(1, published.size());
        assertEquals(ACTIVATION_TIME, outbox.events.get(0).sentAt());
        assertEquals(0, relay.relayPending(published::add));
    }

    private static class TestSimRepository implements SimRepository {

        private final Map<String, Sim> sims = new HashMap<>();

        @Override
        public Optional<Sim> findByIccid(String iccid) {
            return Optional.ofNullable(sims.get(iccid));
        }

        @Override
        public Optional<Sim> findByIccidForUpdate(String iccid) {
            return findByIccid(iccid);
        }

        @Override
        public void save(Sim sim) {
            sims.put(sim.iccid(), sim);
        }
    }

    private static class TestOutboxRepository implements OutboxRepository {

        private final List<OutboxEvent> events = new ArrayList<>();

        @Override
        public void save(OutboxEvent event) {
            events.removeIf(existing -> existing.id().equals(event.id()));
            events.add(event);
        }

        @Override
        public List<OutboxEvent> lockPending(int limit) {
            return events.stream().filter(event -> event.sentAt() == null).limit(limit).toList();
        }
    }
}
