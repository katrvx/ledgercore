create table accounts (
    id         bigint generated always as identity primary key,
    type       varchar(16)  not null,
    owner_name varchar(200) not null,
    currency   char(3)      not null,
    balance    bigint       not null default 0,
    created_at timestamptz  not null default now(),
    constraint accounts_type_known check (type in ('CUSTOMER', 'SYSTEM')),
    constraint accounts_currency_format check (currency ~ '^[A-Z]{3}$'),
    -- only a system funding account may go below zero
    constraint accounts_balance_not_negative check (type = 'SYSTEM' or balance >= 0),
    -- lets transfers and ledger entries reference (id, currency) together
    constraint accounts_id_currency_unique unique (id, currency)
);

create unique index accounts_one_system_account_per_currency on accounts (currency) where type = 'SYSTEM';

-- money enters the ledger from these accounts, one per supported currency
insert into accounts (type, owner_name, currency) values
    ('SYSTEM', 'funding', 'EUR'),
    ('SYSTEM', 'funding', 'GBP'),
    ('SYSTEM', 'funding', 'USD');
