package com.ledgercore.account;

import com.ledgercore.jooq.tables.records.AccountsRecord;
import org.jooq.DSLContext;

import java.util.Optional;

import static com.ledgercore.jooq.Tables.ACCOUNTS;

public class AccountRepository {

    private final DSLContext db;

    public AccountRepository(DSLContext db) {
        this.db = db;
    }

    public Account insertCustomer(String ownerName, String currency) {
        AccountsRecord record = db.insertInto(ACCOUNTS)
                .set(ACCOUNTS.TYPE, AccountType.CUSTOMER.name())
                .set(ACCOUNTS.OWNER_NAME, ownerName)
                .set(ACCOUNTS.CURRENCY, currency)
                .returning()
                .fetchOne();
        return toAccount(record);
    }

    public Optional<Account> findById(long id) {
        return findById(db, id);
    }

    // inside a transaction pass the transaction, otherwise the read takes a second pool connection
    public Optional<Account> findById(DSLContext ctx, long id) {
        return ctx.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .fetchOptional()
                .map(this::toAccount);
    }

    public Optional<Account> findFunding(String currency) {
        return findFunding(db, currency);
    }

    public Optional<Account> findFunding(DSLContext ctx, String currency) {
        return ctx.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.TYPE.eq(AccountType.SYSTEM.name()).and(ACCOUNTS.CURRENCY.eq(currency)))
                .fetchOptional()
                .map(this::toAccount);
    }

    // must run inside a transaction, the row stays locked until it ends
    public Optional<Account> lockForUpdate(DSLContext tx, long id) {
        return tx.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(this::toAccount);
    }

    public void updateBalance(DSLContext tx, long id, long balance) {
        tx.update(ACCOUNTS)
                .set(ACCOUNTS.BALANCE, balance)
                .where(ACCOUNTS.ID.eq(id))
                .execute();
    }

    private Account toAccount(AccountsRecord record) {
        return new Account(
                record.getId(),
                AccountType.valueOf(record.getType()),
                record.getOwnerName(),
                record.getCurrency(),
                record.getBalance(),
                record.getCreatedAt().toInstant());
    }
}
