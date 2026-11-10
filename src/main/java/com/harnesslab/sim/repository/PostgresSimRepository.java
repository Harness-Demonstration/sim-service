package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.Sim;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSimRepository implements SimRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresSimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Sim> findByIccid(String iccid) {
        return jdbcTemplate.query(
                        "SELECT iccid, msisdn, activated_at FROM sim WHERE iccid = ?",
                        (resultSet, rowNumber) -> {
                            var activatedAt = resultSet.getObject("activated_at", OffsetDateTime.class);
                            return new Sim(
                                    resultSet.getString("iccid"),
                                    resultSet.getString("msisdn"),
                                    activatedAt == null ? null : activatedAt.toInstant());
                        },
                        iccid)
                .stream()
                .findFirst();
    }

    @Override
    public void save(Sim sim) {
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            """
                            INSERT INTO sim (iccid, msisdn, activated_at)
                            VALUES (?, ?, ?)
                            ON CONFLICT (iccid) DO UPDATE
                            SET msisdn = EXCLUDED.msisdn, activated_at = EXCLUDED.activated_at
                            """);
                    statement.setString(1, sim.iccid());
                    statement.setString(2, sim.msisdn());
                    if (sim.activatedAt() == null) {
                        statement.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        statement.setObject(
                                3, OffsetDateTime.ofInstant(sim.activatedAt(), ZoneOffset.UTC));
                    }
                    return statement;
                });
    }
}
