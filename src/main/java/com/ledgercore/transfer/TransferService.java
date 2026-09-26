package com.ledgercore.transfer;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.account.Currencies;
import com.ledgercore.http.NotFoundException;
import com.ledgercore.http.UnprocessableException;
import com.ledgercore.http.ValidationException;
import com.ledgercore.ledger.LedgerRepository;
import org.jooq.DSLContext;

public class TransferService {

    private final DSLContext db;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerRepository ledger;

    public TransferService(DSLContext db, AccountRepository accounts, TransferRepository transfers, LedgerRepository ledger) {
        this.db = db;
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
    }

    // moves money between two customer accounts
    public Transfer transfer(CreateTransferRequest request) {
        validate(request);
        long fromId = request.fromAccountId();
        long toId = request.toAccountId();
        if (fromId == toId) {
            throw new UnprocessableException("fromAccountId and toAccountId must be different");
        }
        return db.transactionResult(trx -> {
            DSLContext tx = trx.dsl();
            LockedAccounts locked = lockInIdOrder(tx, fromId, toId);
            requireCustomer(locked.from());
            requireCustomer(locked.to());
            requireCurrency(locked.from(), locked.to(), request.currency());
            return move(tx, locked.from(), locked.to(), request.amount());
        });
    }

    // a deposit is a transfer from the funding account of the same currency
    public Transfer deposit(long accountId, DepositRequest request) {
        validate(request);
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        requireCustomer(account);
        Account funding = accounts.findFunding(account.currency())
                .orElseThrow(() -> new IllegalStateException("no funding account for " + account.currency()));
        return db.transactionResult(trx -> {
            DSLContext tx = trx.dsl();
            LockedAccounts locked = lockInIdOrder(tx, funding.id(), account.id());
            return move(tx, locked.from(), locked.to(), request.amount());
        });
    }

    public Transfer get(long id) {
        return transfers.findById(id)
                .orElseThrow(() -> new NotFoundException("transfer " + id + " not found"));
    }

    private record LockedAccounts(Account from, Account to) {
    }

    // lock in id order so two opposite transfers can't deadlock
    private LockedAccounts lockInIdOrder(DSLContext tx, long fromId, long toId) {
        Account first = lock(tx, Math.min(fromId, toId));
        Account second = lock(tx, Math.max(fromId, toId));
        if (first.id() == fromId) {
            return new LockedAccounts(first, second);
        }
        return new LockedAccounts(second, first);
    }

    private Account lock(DSLContext tx, long id) {
        return accounts.lockForUpdate(tx, id)
                .orElseThrow(() -> new UnprocessableException("account " + id + " not found"));
    }

    // both accounts are locked here, so the balance check and the updates can't race
    private Transfer move(DSLContext tx, Account from, Account to, long amount) {
        if (from.type() == AccountType.CUSTOMER && from.balance() < amount) {
            throw new UnprocessableException("account " + from.id() + " has insufficient funds");
        }
        long fromBalance;
        long toBalance;
        try {
            fromBalance = Math.subtractExact(from.balance(), amount);
            toBalance = Math.addExact(to.balance(), amount);
        } catch (ArithmeticException e) {
            throw new UnprocessableException("amount would overflow an account balance");
        }
        accounts.updateBalance(tx, from.id(), fromBalance);
        accounts.updateBalance(tx, to.id(), toBalance);
        Transfer transfer = transfers.insert(tx, from.id(), to.id(), amount, from.currency(), TransferStatus.COMPLETED);
        ledger.insertPair(tx, transfer.id(), from.id(), to.id(), amount, from.currency());
        return transfer;
    }

    private void requireCustomer(Account account) {
        if (account.type() != AccountType.CUSTOMER) {
            throw new UnprocessableException("account " + account.id() + " is a system account");
        }
    }

    private void requireCurrency(Account from, Account to, String currency) {
        if (!from.currency().equals(to.currency())) {
            throw new UnprocessableException("cross-currency transfers are not supported: account " + from.id()
                    + " is " + from.currency() + " and account " + to.id() + " is " + to.currency());
        }
        if (!from.currency().equals(currency)) {
            throw new UnprocessableException("account " + from.id() + " is " + from.currency() + ", not " + currency);
        }
    }

    private void validate(CreateTransferRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        requirePositive(request.fromAccountId(), "fromAccountId");
        requirePositive(request.toAccountId(), "toAccountId");
        requireAmount(request.amount());
        if (request.currency() == null) {
            throw new ValidationException("currency is required");
        }
        if (!Currencies.isIsoCode(request.currency())) {
            throw new ValidationException("currency must be an ISO 4217 code like EUR");
        }
    }

    private void validate(DepositRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        requireAmount(request.amount());
    }

    private void requirePositive(Long id, String name) {
        if (id == null) {
            throw new ValidationException(name + " is required");
        }
        if (id <= 0) {
            throw new ValidationException(name + " must be a positive number");
        }
    }

    private void requireAmount(Long amount) {
        if (amount == null) {
            throw new ValidationException("amount is required");
        }
        if (amount <= 0) {
            throw new ValidationException("amount must be positive");
        }
    }
}
