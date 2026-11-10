package com.harnesslab.sim.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface JpaSimEntityRepository extends JpaRepository<SimEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SimEntity> findWithLockByIccid(String iccid);
}
