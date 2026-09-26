package com.ledgercore.transfer;

// boxed types so a missing field is null and not a silent zero
public record CreateTransferRequest(Long fromAccountId, Long toAccountId, Long amount, String currency) {
}
