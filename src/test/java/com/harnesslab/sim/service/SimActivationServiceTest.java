package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.harnesslab.sim.types.SimDeactivated;
import com.harnesslab.sim.types.SimOrdered;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
    void a8_deactivating_an_active_sim_publishes_iccid_msisdn_and_deactivation_time() throws Exception {
        service.activate(ICCID);

        service.deactivate(ICCID);

        assertEquals(2, outbox.events.size());
        OutboxEvent row = outbox.events.get(1);
        assertEquals("sim.deactivated", row.topic());
        assertEquals(ICCID, row.aggregateId());
        SimDeactivated event = MAPPER.readValue(row.payload(), SimDeactivated.class);
        assertEquals(new SimDeactivated(row.id(), ICCID, MSISDN, ACTIVATION_TIME), event);
        assertNotEquals(outbox.events.get(0).id(), row.id());
        assertEquals(ACTIVATION_TIME, repository.findByIccid(ICCID).orElseThrow().deactivatedAt());
    }

    @Test
    void a9_deactivating_twice_publishes_once() {
        service.activate(ICCID);
        service.deactivate(ICCID);
        service.deactivate(ICCID);

        assertEquals(2, outbox.events.size());
    }

    @Test
    void a10_deactivating_a_never_activated_sim_is_refused_and_publishes_nothing() {
        assertThrows(SimNotActivatedException.class, () -> service.deactivate(ICCID));

        assertEquals(0, outbox.events.size());
        assertNull(repository.findByIccid(ICCID).orElseThrow().deactivatedAt());
    }

    @Test
    void a10_deactivating_an_unknown_iccid_is_refused_and_publishes_nothing() {
        assertThrows(SimNotFoundException.class, () -> service.deactivate("8949000000000000009"));

        assertEquals(0, outbox.events.size());
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
                return CompletableFuture.failedFuture(new IllegalStateException("Kafka down"));
            }
            published.add(event);
            return CompletableFuture.completedFuture(null);
        });
        assertNull(outbox.events.get(0).sentAt());
        assertEquals(List.of(), published);

        assertEquals(1, relay.relayPending(recording(published)));
        assertEquals(1, published.size());
        assertEquals(ACTIVATION_TIME, outbox.events.get(0).sentAt());
        assertEquals(0, relay.relayPending(recording(published)));
    }

    @Test
    void a5_all_events_are_sent_before_any_acknowledgement_is_awaited() throws Exception {
        service.registerOrdered(new SimOrdered("8949000000000000002", "491700000002", "order-2"));
        service.activate(ICCID);
        service.activate("8949000000000000002");
        OutboxRelayService relay = new OutboxRelayService(outbox, clock);
        List<CompletableFuture<Void>> acknowledgements = new CopyOnWriteArrayList<>();

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(() -> relay.relayPending(event -> {
            CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
            acknowledgements.add(acknowledgement);
            return acknowledgement;
        }));

        long deadline = System.currentTimeMillis() + 5_000;
        while (acknowledgements.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(2, acknowledgements.size());
        assertFalse(run.isDone());
        acknowledgements.forEach(acknowledgement -> acknowledgement.complete(null));
        assertEquals(2, run.get(5, TimeUnit.SECONDS));
    }

    @Test
    void a5_a_failed_send_does_not_stop_the_other_events_from_being_marked_sent() {
        service.registerOrdered(new SimOrdered("8949000000000000002", "491700000002", "order-2"));
        service.activate(ICCID);
        service.activate("8949000000000000002");
        OutboxRelayService relay = new OutboxRelayService(outbox, clock);
        int[] calls = {0};

        int sent = relay.relayPending(event -> calls[0]++ == 0
                ? CompletableFuture.failedFuture(new IllegalStateException("send failed"))
                : CompletableFuture.completedFuture(null));

        assertEquals(1, sent);
        assertEquals(1, outbox.events.stream().filter(event -> event.sentAt() == null).count());
    }

    private static SimEventPublisher recording(List<OutboxEvent> published) {
        return event -> {
            published.add(event);
            return CompletableFuture.completedFuture(null);
        };
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
