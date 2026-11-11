package com.harnesslab.sim.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.service.OutboxRelayService;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.SimOrdered;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "sim.outbox.relay.enabled=false")
class OutboxPersistenceTest {

    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim")
            .withUsername("sim")
            .withPassword("sim");

    @Autowired
    private SimActivationService service;

    @Autowired
    private OutboxRelayService relay;

    @Autowired
    private SimRepository simRepository;

    @SpyBean
    private OutboxRepository outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from sim");
        service.registerOrdered(new SimOrdered(ICCID, MSISDN, "order-1"));
    }

    @Test
    void a4_activation_and_outbox_row_are_committed_together() {
        service.activate(ICCID);

        assertNotNull(simRepository.findByIccid(ICCID).orElseThrow().activatedAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox", Integer.class));
    }

    @Test
    void a4_failing_outbox_insert_leaves_the_sim_inactive() {
        doThrow(new IllegalStateException("outbox insert failed")).when(outbox).save(any());

        assertThrows(RuntimeException.class, () -> service.activate(ICCID));

        assertNull(simRepository.findByIccid(ICCID).orElseThrow().activatedAt());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox", Integer.class));
    }

    @Test
    void a5_failed_publish_leaves_row_pending_and_a_later_run_sends_it() {
        service.activate(ICCID);

        relay.relayPending(event -> CompletableFuture.failedFuture(new IllegalStateException("Kafka down")));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox where sent_at is null", Integer.class));

        List<OutboxEvent> published = new ArrayList<>();
        assertEquals(1, relay.relayPending(event -> {
            published.add(event);
            return CompletableFuture.completedFuture(null);
        }));

        assertEquals(1, published.size());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox where sent_at is null", Integer.class));
    }

    @Test
    void a6_a_second_relay_run_skips_rows_locked_by_the_first() throws Exception {
        service.activate(ICCID);
        CompletableFuture<Void> firstHoldsLock = new CompletableFuture<>();
        CompletableFuture<Void> secondDone = new CompletableFuture<>();

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> relay.relayPending(event -> {
            firstHoldsLock.complete(null);
            return secondDone;
        }));
        firstHoldsLock.get(30, TimeUnit.SECONDS);

        List<OutboxEvent> publishedBySecond = new ArrayList<>();
        int sentBySecond = relay.relayPending(event -> {
            publishedBySecond.add(event);
            return CompletableFuture.completedFuture(null);
        });
        secondDone.complete(null);

        assertEquals(0, sentBySecond);
        assertTrue(publishedBySecond.isEmpty());
        assertEquals(1, first.get(30, TimeUnit.SECONDS));
    }

    @Test
    void a7_two_activations_have_different_event_ids() {
        service.registerOrdered(new SimOrdered("8949000000000000002", "491700000002", "order-2"));

        service.activate(ICCID);
        service.activate("8949000000000000002");

        List<UUID> ids = jdbc.queryForList("select id from outbox", UUID.class);
        assertEquals(2, ids.stream().distinct().count());
        assertEquals(2, jdbc.queryForObject(
                "select count(distinct payload->>'eventId') from outbox", Integer.class));
    }

    @Test
    void a11_failing_outbox_insert_leaves_the_sim_activated() {
        service.activate(ICCID);
        doThrow(new IllegalStateException("outbox insert failed")).when(outbox).save(any());

        assertThrows(RuntimeException.class, () -> service.deactivate(ICCID));

        assertNull(simRepository.findByIccid(ICCID).orElseThrow().deactivatedAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox", Integer.class));
    }

    @Test
    void a11_deactivation_and_outbox_row_are_committed_together() {
        service.activate(ICCID);
        service.deactivate(ICCID);

        assertNotNull(simRepository.findByIccid(ICCID).orElseThrow().deactivatedAt());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outbox where topic = 'sim.deactivated'", Integer.class));
    }

    @Test
    void failed_activation_blocks_deactivation_until_activation_retries() {
        service.activate(ICCID);
        service.deactivate(ICCID);
        List<OutboxEvent> attempted = new ArrayList<>();

        assertEquals(0, relay.relayPending(event -> {
            attempted.add(event);
            return CompletableFuture.failedFuture(new IllegalStateException("Kafka down"));
        }));
        assertEquals(List.of("sim.activated"), attempted.stream().map(OutboxEvent::topic).toList());
        assertEquals(2, jdbc.queryForObject("select count(*) from outbox where sent_at is null", Integer.class));

        assertEquals(1, relay.relayPending(event -> {
            attempted.add(event);
            return CompletableFuture.completedFuture(null);
        }));
        assertEquals(List.of("sim.activated", "sim.activated"), attempted.stream().map(OutboxEvent::topic).toList());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox where sent_at is null", Integer.class));

        assertEquals(1, relay.relayPending(event -> {
            attempted.add(event);
            return CompletableFuture.completedFuture(null);
        }));
        assertEquals(
                List.of("sim.activated", "sim.activated", "sim.deactivated"),
                attempted.stream().map(OutboxEvent::topic).toList());
    }

    @Test
    void failed_deactivation_publish_retries_with_the_same_event_id() {
        service.activate(ICCID);
        relay.relayPending(event -> CompletableFuture.completedFuture(null));
        service.deactivate(ICCID);
        UUID eventId =
                jdbc.queryForObject("select id from outbox where topic = 'sim.deactivated'", UUID.class);
        List<OutboxEvent> attempted = new ArrayList<>();

        assertEquals(0, relay.relayPending(event -> {
            attempted.add(event);
            return CompletableFuture.failedFuture(new IllegalStateException("Kafka down"));
        }));
        assertNull(jdbc.queryForObject(
                "select sent_at from outbox where topic = 'sim.deactivated'", java.time.Instant.class));

        assertEquals(1, relay.relayPending(event -> {
            attempted.add(event);
            return CompletableFuture.completedFuture(null);
        }));
        assertEquals(List.of(eventId, eventId), attempted.stream().map(OutboxEvent::id).toList());
        assertEquals("sim.deactivated", attempted.get(0).topic());
        assertNotNull(jdbc.queryForObject(
                "select sent_at from outbox where topic = 'sim.deactivated'", java.time.Instant.class));
    }
}
