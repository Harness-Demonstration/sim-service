-- Debezium now publishes the outbox from the write-ahead log (docs/design-docs/outbox.md).
-- A row is inserted and deleted in the same transaction, so the polling relay's columns and indexes are gone.
drop index outbox_pending_idx;
drop index outbox_aggregate_sequence_idx;
alter table outbox drop column sent_at;
alter table outbox drop column aggregate_sequence;
alter table outbox drop column trace_context;

-- Span context of the transaction that wrote the row, as java.util.Properties text, for example
-- "traceparent=00-...". Debezium's outbox event router continues the trace from it.
alter table outbox add column tracingspancontext text;

-- The publication says which tables Debezium may read: only the outbox. The service owns this decision, so the
-- migration creates it; the connector does not create publications (publication.autocreate.mode=disabled).
create publication sim_outbox for table outbox;
