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
        return db.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .fetchOptional()
                .map(this::toAccount);
    }

    public boolean hasFundingAccount(String currency) {
        return db.fetchExists(ACCOUNTS,
                ACCOUNTS.TYPE.eq(AccountType.SYSTEM.name()).and(ACCOUNTS.CURRENCY.eq(currency)));
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
