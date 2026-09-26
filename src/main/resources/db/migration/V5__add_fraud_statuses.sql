alter table transfers drop constraint transfers_status_known;
alter table transfers add constraint transfers_status_known check (status in ('COMPLETED', 'PENDING_REVIEW', 'DECLINED'));

-- why a transfer was flagged, for internal review only and never sent to the client
alter table transfers add column fraud_reason varchar(500);

-- the velocity fallback counts transfers per account in a time window
drop index transfers_from_account_id_idx;
create index transfers_from_account_id_created_at_idx on transfers (from_account_id, created_at);
