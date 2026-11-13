package com.harnesslab.sim.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimOrdered;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the outbox writes to the write-ahead log, which is all Debezium sees. PostgreSQL's built-in test_decoding
 * plugin shows the log as text, so these tests need no Kafka and no Debezium (docs/design-docs/outbox.md).
 */
@Testcontainers
@SpringBootTest
class OutboxPersistenceTest {

    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";
    private static final String SLOT = "outbox_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim")
            .withUsername("sim")
            .withPassword("sim")
            .withCommand("postgres", "-c", "wal_level=logical");

    @Autowired
    private SimActivationService service;

    @Autowired
    private SimRepository simRepository;

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
        jdbc.update("delete from sim");
        service.registerOrdered(new SimOrdered(ICCID, MSISDN, "order-1"));
        jdbc.queryForList("select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = ?", SLOT);
        jdbc.queryForList("select pg_create_logical_replication_slot(?, 'test_decoding')", SLOT);
    }

    @Test
    void a4_activation_and_its_outbox_insert_are_committed_together() {
        service.activate(ICCID);

        assertNotNull(simRepository.findByIccid(ICCID).orElseThrow().activatedAt());
        List<String> transaction = committedChanges();
        assertTrue(transaction.stream().anyMatch(change -> change.startsWith("table public.sim: UPDATE:")), transaction::toString);
        assertTrue(transaction.stream().anyMatch(change -> isOutboxInsert(change, "sim.activated")), transaction::toString);
    }

    @Test
    void a4_failing_outbox_insert_leaves_the_sim_inactive_and_writes_no_event() {
        rejectOutboxInserts(() -> assertThrows(RuntimeException.class, () -> service.activate(ICCID)));

        assertNull(simRepository.findByIccid(ICCID).orElseThrow().activatedAt());
        assertTrue(committedChanges().stream().noneMatch(change -> change.startsWith("table public.outbox")));
    }

    @Test
    void a11_deactivation_and_its_outbox_insert_are_committed_together() {
        service.activate(ICCID);
        committedChanges();

        service.deactivate(ICCID);

        assertNotNull(simRepository.findByIccid(ICCID).orElseThrow().deactivatedAt());
        assertTrue(committedChanges().stream().anyMatch(change -> isOutboxInsert(change, "sim.deactivated")));
    }

    @Test
    void a11_failing_outbox_insert_leaves_the_sim_activated() {
        service.activate(ICCID);
        committedChanges();
        rejectOutboxInserts(() -> assertThrows(RuntimeException.class, () -> service.deactivate(ICCID)));

        assertNull(simRepository.findByIccid(ICCID).orElseThrow().deactivatedAt());
        assertTrue(committedChanges().stream().noneMatch(change -> change.startsWith("table public.outbox")));
    }

    @Test
    void outbox_row_is_deleted_in_the_same_transaction_so_the_table_stays_empty() {
        service.activate(ICCID);

        List<String> transaction = committedChanges();
        int insert = indexOf(transaction, "table public.outbox: INSERT:");
        int delete = indexOf(transaction, "table public.outbox: DELETE:");
        assertTrue(insert >= 0 && delete > insert, transaction::toString);
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox", Integer.class));
    }

    @Test
    void outbox_insert_carries_no_span_context_without_a_current_span() {
        service.activate(ICCID);

        String insert = committedChanges().stream()
                .filter(change -> change.startsWith("table public.outbox: INSERT:")).findFirst().orElseThrow();
        assertTrue(insert.contains("tracingspancontext[text]:null"), insert);
        assertFalse(insert.contains("traceparent"), insert);
    }

    /** Runs the action while the database rejects every outbox insert, as it would on a constraint or disk error. */
    private void rejectOutboxInserts(Runnable action) {
        jdbc.execute("alter table outbox add constraint reject_all_inserts check (false) not valid");
        try {
            action.run();
        } finally {
            jdbc.execute("alter table outbox drop constraint reject_all_inserts");
        }
    }

    /** Reads and consumes what was committed since the last call, one entry per change, BEGIN and COMMIT included. */
    private List<String> committedChanges() {
        return jdbc.queryForList("select data from pg_logical_slot_get_changes(?, null, null)", String.class, SLOT);
    }

    private static boolean isOutboxInsert(String change, String topic) {
        return change.startsWith("table public.outbox: INSERT:")
                && change.contains("topic[character varying]:'" + topic + "'");
    }

    private static int indexOf(List<String> changes, String prefix) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
