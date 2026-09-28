package com.ledgercore.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

// error body from rfc 9457 (it replaced rfc 7807), requestId is an extension member the rfc allows
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String requestId) {

    // without a request id: saved idempotent responses are replayed byte for byte to later requests
    public static Problem of(int status, String detail) {
        return new Problem("about:blank", title(status), status, detail, null);
    }

    public static Problem forCurrentRequest(int status, String detail) {
        return new Problem("about:blank", title(status), status, detail, MDC.get(RequestFilters.REQUEST_ID));
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Content";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }
}
