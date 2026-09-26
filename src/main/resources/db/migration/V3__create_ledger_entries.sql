-- amount is signed: negative for the debit side, positive for the credit side
create table ledger_entries (
    id          bigint generated always as identity primary key,
    transfer_id bigint      not null,
    account_id  bigint      not null,
    amount      bigint      not null,
    currency    char(3)     not null,
    created_at  timestamptz not null default now(),
    constraint ledger_entries_amount_not_zero check (amount <> 0),
    constraint ledger_entries_transfer_fk foreign key (transfer_id) references transfers (id),
    constraint ledger_entries_account_fk foreign key (account_id, currency) references accounts (id, currency)
);

create index ledger_entries_transfer_id_idx on ledger_entries (transfer_id);
create index ledger_entries_account_id_idx on ledger_entries (account_id, id);
