package com.ledgercore.idempotency;

import com.ledgercore.jooq.tables.records.IdempotencyKeysRecord;
import org.jooq.DSLContext;

import java.util.Optional;

import static com.ledgercore.jooq.Tables.IDEMPOTENCY_KEYS;

public class IdempotencyRepository {

    private final DSLContext db;

    public IdempotencyRepository(DSLContext db) {
        this.db = db;
    }

    public Optional<IdempotencyRecord> find(String key) {
        return db.selectFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.KEY.eq(key))
                .fetchOptional()
                .map(this::toRecord);
    }

    // the primary key on key is the final guard against a duplicate, even when redis is down
    public void insert(DSLContext tx, String key, IdempotencyRecord record) {
        tx.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.KEY, key)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, record.requestHash())
                .set(IDEMPOTENCY_KEYS.STATUS, record.response().status())
                .set(IDEMPOTENCY_KEYS.LOCATION, record.response().location())
                .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, record.response().body())
                .execute();
    }

    private IdempotencyRecord toRecord(IdempotencyKeysRecord row) {
        return new IdempotencyRecord(
                row.getRequestHash(),
                new StoredResponse(row.getStatus(), row.getLocation(), row.getResponseBody()));
    }
}
