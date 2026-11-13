package com.harnesslab.sim.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOutboxEntityRepository extends JpaRepository<OutboxEntity, UUID> {
}
