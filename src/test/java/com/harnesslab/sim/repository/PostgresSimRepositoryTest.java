package com.harnesslab.sim.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harnesslab.sim.types.Sim;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class PostgresSimRepositoryTest {

    private static final AtomicLong ICCID_SEQUENCE = new AtomicLong();
    private static final String MSISDN = "491700000001";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim")
            .withUsername("sim")
            .withPassword("sim");

    @Autowired
    private PostgresSimRepository repository;

    @Autowired
    private DataSource dataSource;

    private String iccid;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setUp() {
        iccid = "894900000000000000" + ICCID_SEQUENCE.incrementAndGet();
    }

    @Test
    void saves_and_retrieves_an_inactive_sim() {
        Sim sim = new Sim(iccid, MSISDN, null);

        repository.save(sim);

        assertEquals(sim, repository.findByIccid(iccid).orElseThrow());
    }

    @Test
    void saves_and_retrieves_activation_time() {
        Instant activatedAt = Instant.parse("2026-01-02T03:04:05Z");
        Sim sim = new Sim(iccid, MSISDN, activatedAt);

        repository.save(sim);

        assertEquals(sim, repository.findByIccid(iccid).orElseThrow());
    }

    @Test
    void saving_an_existing_iccid_updates_the_sim() {
        repository.save(new Sim(iccid, MSISDN, null));
        Sim updated = new Sim(iccid, "491700000002", Instant.parse("2026-01-02T03:04:05Z"));

        repository.save(updated);

        assertEquals(updated, repository.findByIccid(iccid).orElseThrow());
    }

    @Test
    void returns_empty_for_an_unknown_iccid() {
        assertFalse(repository.findByIccid(iccid).isPresent());
    }

    @Test
    void schema_enforces_iccid_primary_key_and_non_null_msisdn() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet keys = metadata.getPrimaryKeys(connection.getCatalog(), "public", "sim")) {
                assertTrue(keys.next());
                assertEquals("iccid", keys.getString("COLUMN_NAME"));
            }
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), "public", "sim", "msisdn")) {
                assertTrue(columns.next());
                assertEquals(DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE"));
            }
        }
    }
}
