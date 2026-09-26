package com.ledgercore.account;

import com.ledgercore.http.NotFoundException;
import com.ledgercore.http.ValidationException;

public class AccountService {

    private static final int MAX_OWNER_NAME_LENGTH = 200;

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account create(CreateAccountRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        String ownerName = validOwnerName(request.ownerName());
        String currency = validCurrency(request.currency());
        return accounts.insertCustomer(ownerName, currency);
    }

    public Account get(long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("account " + id + " not found"));
    }

    private String validOwnerName(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            throw new ValidationException("ownerName is required");
        }
        String trimmed = ownerName.trim();
        if (trimmed.length() > MAX_OWNER_NAME_LENGTH) {
            throw new ValidationException("ownerName must be at most " + MAX_OWNER_NAME_LENGTH + " characters");
        }
        for (char c : trimmed.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw new ValidationException("ownerName must not contain control characters");
            }
        }
        return trimmed;
    }

    // a currency is supported only if it has a funding account
    private String validCurrency(String currency) {
        if (currency == null) {
            throw new ValidationException("currency is required");
        }
        if (!Currencies.isIsoCode(currency)) {
            throw new ValidationException("currency must be an ISO 4217 code like EUR");
        }
        if (accounts.findFunding(currency).isEmpty()) {
            throw new ValidationException("currency " + currency + " is not supported");
        }
        return currency;
    }
}
