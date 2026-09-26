package com.ledgercore.transfer;

import java.time.Instant;

public record Transfer(long id, long fromAccountId, long toAccountId, long amount, String currency,
                       TransferStatus status, Instant createdAt) {
}
