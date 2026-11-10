package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.Sim;
import java.util.Optional;

public interface SimRepository {
    Optional<Sim> findByIccid(String iccid);

    void save(Sim sim);
}
