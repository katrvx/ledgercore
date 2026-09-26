package com.ledgercore.http;

// error body from rfc 7807
public record Problem(String type, String title, int status, String detail) {

    public static Problem of(int status, String detail) {
        return new Problem("about:blank", title(status), status, detail);
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Content";
            default -> "Error";
        };
    }
}
