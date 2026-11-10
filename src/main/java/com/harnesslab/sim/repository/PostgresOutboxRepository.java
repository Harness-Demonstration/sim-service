package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.OutboxEvent;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresOutboxRepository implements OutboxRepository {

    private final JpaOutboxEntityRepository jpaRepository;

    public PostgresOutboxRepository(JpaOutboxEntityRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(OutboxEvent event) {
        jpaRepository.save(new OutboxEntity(event));
    }

    @Override
    public List<OutboxEvent> lockPending(int limit) {
        return jpaRepository.findPending(PageRequest.of(0, limit)).stream()
                .map(OutboxEntity::toDomain)
                .toList();
    }
}
