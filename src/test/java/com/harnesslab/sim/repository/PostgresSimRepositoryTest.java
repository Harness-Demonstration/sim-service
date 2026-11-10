package com.harnesslab.sim.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.harnesslab.sim.types.Sim;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresSimRepositoryTest {

    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("sim")
            .withUsername("sim")
            .withPassword("sim");

    private PostgresSimRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("DELETE FROM sim");
        repository = new PostgresSimRepository(jdbcTemplate);
    }

    @Test
    void c5_saves_and_retrieves_an_inactive_sim() {
        Sim sim = new Sim(ICCID, MSISDN, null);

        repository.save(sim);

        assertEquals(sim, repository.findByIccid(ICCID).orElseThrow());
    }

    @Test
    void c5_saves_and_retrieves_activation_time() {
        Instant activatedAt = Instant.parse("2026-01-02T03:04:05Z");
        Sim sim = new Sim(ICCID, MSISDN, activatedAt);

        repository.save(sim);

        assertEquals(sim, repository.findByIccid(ICCID).orElseThrow());
    }

    @Test
    void c5_saving_an_existing_iccid_updates_the_sim() {
        repository.save(new Sim(ICCID, MSISDN, null));
        Sim updated = new Sim(ICCID, "491700000002", Instant.parse("2026-01-02T03:04:05Z"));

        repository.save(updated);

        assertEquals(updated, repository.findByIccid(ICCID).orElseThrow());
    }

    @Test
    void c5_returns_empty_for_an_unknown_iccid() {
        assertFalse(repository.findByIccid(ICCID).isPresent());
    }

    @Test
    void c5_schema_enforces_iccid_primary_key_and_non_null_msisdn() {
        jdbcTemplate.update("INSERT INTO sim (iccid, msisdn) VALUES (?, ?)", ICCID, MSISDN);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("INSERT INTO sim (iccid, msisdn) VALUES (?, ?)", ICCID, MSISDN));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("INSERT INTO sim (iccid, msisdn) VALUES (?, NULL)", "8949000000000000002"));
    }
}
