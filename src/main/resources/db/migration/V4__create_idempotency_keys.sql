-- one row per Idempotency-Key with the response to replay, kept even when redis loses it
create table idempotency_keys (
    key           varchar(255) primary key,
    request_hash  char(64)     not null,
    status        int          not null,
    location      varchar(64),
    response_body text         not null,
    created_at    timestamptz  not null default now()
);
