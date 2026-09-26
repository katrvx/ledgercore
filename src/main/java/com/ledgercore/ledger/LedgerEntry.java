package com.ledgercore.ledger;

import java.time.Instant;

// one line of an account's history, amount is negative when money left the account
public record LedgerEntry(long entryId, long transferId, long counterpartyAccountId, long amount, String currency,
                          Instant createdAt) {
}
