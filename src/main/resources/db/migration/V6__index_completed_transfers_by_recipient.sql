-- the new recipient rule asks on every transfer if this sender has paid that recipient before
create index transfers_completed_from_to_idx on transfers (from_account_id, to_account_id) where status = 'COMPLETED';
