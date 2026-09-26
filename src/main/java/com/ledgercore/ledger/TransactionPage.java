package com.ledgercore.ledger;

import java.util.List;

// nextCursor is null on the last page
public record TransactionPage(List<LedgerEntry> items, String nextCursor) {
}
