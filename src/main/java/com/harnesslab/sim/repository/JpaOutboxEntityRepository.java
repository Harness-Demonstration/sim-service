package com.harnesslab.sim.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface JpaOutboxEntityRepository extends JpaRepository<OutboxEntity, UUID> {

    // jakarta.persistence.lock.timeout -2 is Hibernate's SKIP LOCKED
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e from OutboxEntity e
            where e.sentAt is null
              and not exists (
                  select prior.id from OutboxEntity prior
                  where prior.aggregateId = e.aggregateId
                    and prior.aggregateSequence < e.aggregateSequence
                    and prior.sentAt is null
              )
            order by e.createdAt, e.id
            """)
    List<OutboxEntity> findPending(Pageable pageable);
}
