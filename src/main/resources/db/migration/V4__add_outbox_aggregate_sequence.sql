alter table outbox
    add column aggregate_sequence bigint not null default 1;

create unique index outbox_aggregate_sequence_idx
    on outbox (aggregate_id, aggregate_sequence);
