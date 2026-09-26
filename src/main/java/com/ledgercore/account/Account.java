package com.ledgercore.account;

import java.time.Instant;

// balance is in minor units, for example cents for EUR
public record Account(long id, AccountType type, String ownerName, String currency, long balance, Instant createdAt) {
}
