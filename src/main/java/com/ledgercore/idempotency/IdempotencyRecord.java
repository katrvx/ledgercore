package com.ledgercore.idempotency;

// what redis and the database remember about one Idempotency-Key
public record IdempotencyRecord(String requestHash, StoredResponse response) {
}
