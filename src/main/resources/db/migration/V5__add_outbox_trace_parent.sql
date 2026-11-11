-- W3C traceparent of the span that wrote the row, so the relay can continue that trace.
alter table outbox
    add column trace_parent varchar(55);
