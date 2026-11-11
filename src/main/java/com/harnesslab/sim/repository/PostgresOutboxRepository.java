package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresOutboxRepository implements OutboxRepository {

    private final JpaOutboxEntityRepository jpaRepository;
    private final Tracer tracer;

    public PostgresOutboxRepository(JpaOutboxEntityRepository jpaRepository, ObjectProvider<Tracer> tracer) {
        this.jpaRepository = jpaRepository;
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    /** A new row remembers the current span as its traceParent; an existing row keeps the one it has. */
    @Override
    public void save(OutboxEvent event) {
        String traceParent = event.traceParent() != null ? event.traceParent() : currentTraceParent();
        jpaRepository.save(new OutboxEntity(event, traceParent));
    }

    @Override
    public List<OutboxEvent> lockPending(int limit) {
        return jpaRepository.findPending(PageRequest.of(0, limit)).stream()
                .map(OutboxEntity::toDomain)
                .toList();
    }

    private String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null || span.context().traceId().isEmpty()) {
            return null;
        }
        TraceContext context = span.context();
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }
}
