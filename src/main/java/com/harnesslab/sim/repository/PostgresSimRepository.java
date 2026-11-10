package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.Sim;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSimRepository implements SimRepository {

    private final JpaSimEntityRepository jpaRepository;

    public PostgresSimRepository(JpaSimEntityRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Sim> findByIccid(String iccid) {
        return jpaRepository.findById(iccid).map(SimEntity::toDomain);
    }

    @Override
    public void save(Sim sim) {
        jpaRepository.save(new SimEntity(sim));
    }
}
