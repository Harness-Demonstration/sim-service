-- Trace headers (W3C traceparent, tracestate, baggage) of the span that wrote the row, so the relay continues that trace.
alter table outbox
    add column trace_context jsonb;
