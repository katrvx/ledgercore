package com.ledgercore.http;

// the same request is being handled right now
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
