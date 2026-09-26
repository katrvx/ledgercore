package com.ledgercore.http;

// error body from rfc 7807
public record Problem(String type, String title, int status, String detail) {
}
