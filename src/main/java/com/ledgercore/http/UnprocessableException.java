package com.ledgercore.http;

// the request is well formed but breaks a business rule
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
