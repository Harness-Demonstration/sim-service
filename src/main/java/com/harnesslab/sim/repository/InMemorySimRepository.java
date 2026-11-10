package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.Sim;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySimRepository implements SimRepository {

    private final ConcurrentMap<String, Sim> sims = new ConcurrentHashMap<>();

    @Override
    public Optional<Sim> findByIccid(String iccid) {
        return Optional.ofNullable(sims.get(iccid));
    }

    @Override
    public void save(Sim sim) {
        sims.put(sim.iccid(), sim);
    }
}
