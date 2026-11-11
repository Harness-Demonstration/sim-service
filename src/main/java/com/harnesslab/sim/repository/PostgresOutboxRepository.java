package com.harnesslab.sim.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.types.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresOutboxRepository implements OutboxRepository {

    private final JpaOutboxEntityRepository jpaRepository;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObjectMapper objectMapper;

    /** Without tracing (for example in tests) there is no Propagator bean; Propagator.NOOP writes no headers. */
    public PostgresOutboxRepository(
            JpaOutboxEntityRepository jpaRepository,
            Tracer tracer,
            ObjectProvider<Propagator> propagator,
            ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.tracer = tracer;
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
        this.objectMapper = objectMapper;
    }

    /** A new row stores the trace headers of the current span; an existing row keeps the ones it has. */
    @Override
    public void save(OutboxEvent event) {
        String traceContext = event.traceContext() != null ? event.traceContext() : currentTraceContext();
        jpaRepository.save(new OutboxEntity(event, traceContext));
    }

    @Override
    public List<OutboxEvent> lockPending(int limit) {
        return jpaRepository.findPending(PageRequest.of(0, limit)).stream()
                .map(OutboxEntity::toDomain)
                .toList();
    }

    private String currentTraceContext() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        if (headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize trace headers", exception);
        }
    }
}
