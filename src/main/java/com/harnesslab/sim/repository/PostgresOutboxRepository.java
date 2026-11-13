package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox as Debezium expects it (docs/design-docs/outbox.md): the row is inserted and deleted again in the caller's
 * transaction. Debezium reads the insert from the write-ahead log and ignores the delete, so the table stays empty.
 */
@Repository
public class PostgresOutboxRepository implements OutboxRepository {

    private final JpaOutboxEntityRepository jpaRepository;
    private final Tracer tracer;
    private final Propagator propagator;

    /** Without tracing (for example in tests) there is no Propagator bean; Propagator.NOOP writes no context. */
    public PostgresOutboxRepository(
            JpaOutboxEntityRepository jpaRepository, Tracer tracer, ObjectProvider<Propagator> propagator) {
        this.jpaRepository = jpaRepository;
        this.tracer = tracer;
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(OutboxEvent event) {
        OutboxEntity row = new OutboxEntity(event, currentSpanContext());
        // Flush so the INSERT is executed before the DELETE; Debezium needs the insert in the write-ahead log.
        jpaRepository.saveAndFlush(row);
        jpaRepository.delete(row);
    }

    /** The current span as java.util.Properties text ("traceparent=00-..."), the format Debezium 3.7 reads. */
    private String currentSpanContext() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Properties context = new Properties();
        propagator.inject(span.context(), context, Properties::setProperty);
        if (context.isEmpty()) {
            return null;
        }
        StringWriter text = new StringWriter();
        try {
            context.store(text, null);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        // Properties.store starts with a date comment; Debezium ignores it, but the column is easier to read without.
        return text.toString().lines().filter(line -> !line.startsWith("#")).collect(Collectors.joining("\n"));
    }
}
