package com.ledgercore.ledger;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.ArrayList;
import java.util.List;

import static com.ledgercore.jooq.Tables.LEDGER_ENTRIES;
import static com.ledgercore.jooq.Tables.TRANSFERS;

public class LedgerRepository {

    private final DSLContext db;

    public LedgerRepository(DSLContext db) {
        this.db = db;
    }

    // every transfer writes exactly two entries: minus for the sender, plus for the receiver
    public void insertPair(DSLContext tx, long transferId, long fromAccountId, long toAccountId, long amount,
                           String currency) {
        tx.insertInto(LEDGER_ENTRIES,
                        LEDGER_ENTRIES.TRANSFER_ID, LEDGER_ENTRIES.ACCOUNT_ID, LEDGER_ENTRIES.AMOUNT, LEDGER_ENTRIES.CURRENCY)
                .values(transferId, fromAccountId, -amount, currency)
                .values(transferId, toAccountId, amount, currency)
                .execute();
    }

    // keyset paging: "older than this id" uses the (account_id, id) index on every page, however deep
    public List<LedgerEntry> findPage(long accountId, Long beforeEntryId, int limit) {
        Condition condition = LEDGER_ENTRIES.ACCOUNT_ID.eq(accountId);
        if (beforeEntryId != null) {
            condition = condition.and(LEDGER_ENTRIES.ID.lt(beforeEntryId));
        }
        List<LedgerEntry> entries = new ArrayList<>();
        for (Record row : db.select(LEDGER_ENTRIES.ID, LEDGER_ENTRIES.TRANSFER_ID, LEDGER_ENTRIES.AMOUNT,
                        LEDGER_ENTRIES.CURRENCY, LEDGER_ENTRIES.CREATED_AT, TRANSFERS.FROM_ACCOUNT_ID, TRANSFERS.TO_ACCOUNT_ID)
                .from(LEDGER_ENTRIES)
                .join(TRANSFERS).on(TRANSFERS.ID.eq(LEDGER_ENTRIES.TRANSFER_ID))
                .where(condition)
                .orderBy(LEDGER_ENTRIES.ID.desc())
                .limit(limit)
                .fetch()) {
            entries.add(toEntry(accountId, row));
        }
        return entries;
    }

    private LedgerEntry toEntry(long accountId, Record row) {
        long from = row.get(TRANSFERS.FROM_ACCOUNT_ID);
        long to = row.get(TRANSFERS.TO_ACCOUNT_ID);
        long counterparty = from == accountId ? to : from;
        return new LedgerEntry(
                row.get(LEDGER_ENTRIES.ID),
                row.get(LEDGER_ENTRIES.TRANSFER_ID),
                counterparty,
                row.get(LEDGER_ENTRIES.AMOUNT),
                row.get(LEDGER_ENTRIES.CURRENCY),
                row.get(LEDGER_ENTRIES.CREATED_AT).toInstant());
    }
}
