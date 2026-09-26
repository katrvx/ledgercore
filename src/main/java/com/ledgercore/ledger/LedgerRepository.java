package com.ledgercore.ledger;

import org.jooq.DSLContext;

import static com.ledgercore.jooq.Tables.LEDGER_ENTRIES;

public class LedgerRepository {

    // every transfer writes exactly two entries: minus for the sender, plus for the receiver
    public void insertPair(DSLContext tx, long transferId, long fromAccountId, long toAccountId, long amount,
                           String currency) {
        tx.insertInto(LEDGER_ENTRIES,
                        LEDGER_ENTRIES.TRANSFER_ID, LEDGER_ENTRIES.ACCOUNT_ID, LEDGER_ENTRIES.AMOUNT, LEDGER_ENTRIES.CURRENCY)
                .values(transferId, fromAccountId, -amount, currency)
                .values(transferId, toAccountId, amount, currency)
                .execute();
    }
}
