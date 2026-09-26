create table transfers (
    id              bigint generated always as identity primary key,
    from_account_id bigint      not null,
    to_account_id   bigint      not null,
    amount          bigint      not null,
    currency        char(3)     not null,
    status          varchar(16) not null,
    created_at      timestamptz not null default now(),
    constraint transfers_amount_positive check (amount > 0),
    constraint transfers_different_accounts check (from_account_id <> to_account_id),
    constraint transfers_status_known check (status in ('COMPLETED')),
    -- both accounts must be in the transfer currency
    constraint transfers_from_account_fk foreign key (from_account_id, currency) references accounts (id, currency),
    constraint transfers_to_account_fk foreign key (to_account_id, currency) references accounts (id, currency)
);

create index transfers_from_account_id_idx on transfers (from_account_id);
create index transfers_to_account_id_idx on transfers (to_account_id);
