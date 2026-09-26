package com.ledgercore.transfer;

import com.ledgercore.jooq.tables.records.TransfersRecord;
import org.jooq.DSLContext;

import java.util.Optional;

import static com.ledgercore.jooq.Tables.TRANSFERS;

public class TransferRepository {

    private final DSLContext db;

    public TransferRepository(DSLContext db) {
        this.db = db;
    }

    public Transfer insert(DSLContext tx, long fromAccountId, long toAccountId, long amount, String currency,
                           TransferStatus status, String fraudReason) {
        TransfersRecord record = tx.insertInto(TRANSFERS)
                .set(TRANSFERS.FROM_ACCOUNT_ID, fromAccountId)
                .set(TRANSFERS.TO_ACCOUNT_ID, toAccountId)
                .set(TRANSFERS.AMOUNT, amount)
                .set(TRANSFERS.CURRENCY, currency)
                .set(TRANSFERS.STATUS, status.name())
                .set(TRANSFERS.FRAUD_REASON, fraudReason)
                .returning()
                .fetchOne();
        return toTransfer(record);
    }

    public Optional<Transfer> findById(long id) {
        return db.selectFrom(TRANSFERS)
                .where(TRANSFERS.ID.eq(id))
                .fetchOptional()
                .map(this::toTransfer);
    }

    private Transfer toTransfer(TransfersRecord record) {
        return new Transfer(
                record.getId(),
                record.getFromAccountId(),
                record.getToAccountId(),
                record.getAmount(),
                record.getCurrency(),
                TransferStatus.valueOf(record.getStatus()),
                record.getCreatedAt().toInstant());
    }
}
