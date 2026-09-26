package com.ledgercore.idempotency;

// the part of an http response that a retry gets back
public record StoredResponse(int status, String location, String body) {
}
