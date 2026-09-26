package com.ledgercore.ledger;

import com.ledgercore.account.AccountRepository;
import com.ledgercore.http.NotFoundException;
import com.ledgercore.http.ValidationException;

import java.util.ArrayList;
import java.util.List;

public class LedgerService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    // newest entries first, one page at a time
    public TransactionPage history(long accountId, String limitParam, String cursorParam) {
        int limit = parseLimit(limitParam);
        Long before = cursorParam == null ? null : Cursor.decode(cursorParam);
        if (accounts.findById(accountId).isEmpty()) {
            throw new NotFoundException("account " + accountId + " not found");
        }
        // one extra row tells if there is a next page without a second query
        List<LedgerEntry> rows = ledger.findPage(accountId, before, limit + 1);
        if (rows.size() <= limit) {
            return new TransactionPage(rows, null);
        }
        List<LedgerEntry> page = new ArrayList<>(rows.subList(0, limit));
        return new TransactionPage(page, Cursor.encode(page.get(limit - 1).entryId()));
    }

    private int parseLimit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw badLimit();
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw badLimit();
        }
        return limit;
    }

    private ValidationException badLimit() {
        return new ValidationException("limit must be a number from 1 to " + MAX_LIMIT);
    }
}
