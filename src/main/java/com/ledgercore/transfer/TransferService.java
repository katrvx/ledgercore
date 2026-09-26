package com.ledgercore.transfer;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.account.Currencies;
import com.ledgercore.fraud.FraudEngine;
import com.ledgercore.fraud.FraudFactsCollector;
import com.ledgercore.fraud.RuleResult;
import com.ledgercore.http.NotFoundException;
import com.ledgercore.http.UnprocessableException;
import com.ledgercore.http.ValidationException;
import com.ledgercore.ledger.LedgerRepository;
import org.jooq.DSLContext;

// the caller opens the transaction and passes it in, so the idempotency row can join it
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerRepository ledger;
    private final FraudFactsCollector fraudFacts;
    private final FraudEngine fraudEngine;

    public TransferService(AccountRepository accounts, TransferRepository transfers, LedgerRepository ledger,
                           FraudFactsCollector fraudFacts, FraudEngine fraudEngine) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.fraudFacts = fraudFacts;
        this.fraudEngine = fraudEngine;
    }

    // moves money between two customer accounts, unless the fraud rules stop it
    public Transfer transfer(DSLContext tx, CreateTransferRequest request) {
        validate(request);
        long fromId = request.fromAccountId();
        long toId = request.toAccountId();
        long amount = request.amount();
        if (fromId == toId) {
            throw new UnprocessableException("fromAccountId and toAccountId must be different");
        }
        // type and currency never change, so a plain read is enough to check them
        Account from = find(tx, fromId);
        Account to = find(tx, toId);
        requireCustomer(from);
        requireCustomer(to);
        requireCurrency(from, to, request.currency());

        // fraud queries run before locking, so they don't make the lock last longer
        RuleResult risk = fraudEngine.evaluate(fraudFacts.collect(tx, fromId, toId, amount));
        return switch (risk.decision()) {
            case APPROVE -> lockAndMove(tx, fromId, toId, amount);
            case REVIEW -> transfers.insert(tx, fromId, toId, amount, from.currency(), TransferStatus.PENDING_REVIEW, risk.reason());
            case DECLINE -> transfers.insert(tx, fromId, toId, amount, from.currency(), TransferStatus.DECLINED, risk.reason());
        };
    }

    // a deposit is a transfer from the funding account of the same currency, money from outside is not checked for fraud
    public Transfer deposit(DSLContext tx, long accountId, DepositRequest request) {
        validate(request);
        Account account = accounts.findById(tx, accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        requireCustomer(account);
        Account funding = accounts.findFunding(tx, account.currency())
                .orElseThrow(() -> new IllegalStateException("no funding account for " + account.currency()));
        return lockAndMove(tx, funding.id(), account.id(), request.amount());
    }

    public Transfer get(long id) {
        return transfers.findById(id)
                .orElseThrow(() -> new NotFoundException("transfer " + id + " not found"));
    }

    private Account find(DSLContext tx, long id) {
        return accounts.findById(tx, id)
                .orElseThrow(() -> new UnprocessableException("account " + id + " not found"));
    }

    // lock in id order so two opposite transfers can't deadlock
    private Transfer lockAndMove(DSLContext tx, long fromId, long toId, long amount) {
        Account first = lock(tx, Math.min(fromId, toId));
        Account second = lock(tx, Math.max(fromId, toId));
        if (first.id() == fromId) {
            return move(tx, first, second, amount);
        }
        return move(tx, second, first, amount);
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
        Transfer transfer = transfers.insert(tx, from.id(), to.id(), amount, from.currency(), TransferStatus.COMPLETED, null);
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
