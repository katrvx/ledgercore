package com.ledgercore.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import spark.Service;

import java.util.UUID;
import java.util.regex.Pattern;

public class RequestFilters {

    public static final String REQUEST_ID = "requestId";

    private static final Logger access = LoggerFactory.getLogger("access");
    // a client id goes into our logs, so only short and plain ones are kept
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String STARTED_AT = "ledgercore.startedAt";

    public void register(Service http) {
        http.before((req, res) -> {
            String requestId = requestId(req.headers("X-Request-Id"));
            MDC.put(REQUEST_ID, requestId);
            res.header("X-Request-Id", requestId);
            req.attribute(STARTED_AT, System.nanoTime());
        });

        // runs even when the route threw, and jetty reuses threads, so the MDC must be empty afterwards
        http.afterAfter((req, res) -> {
            try {
                Long startedAt = req.attribute(STARTED_AT);
                long millis = startedAt == null ? 0 : (System.nanoTime() - startedAt) / 1_000_000;
                // method, path, status and time only: bodies can hold names and amounts
                access.info("{} {} {} {}ms", req.requestMethod(), req.pathInfo(), res.status(), millis);
            } finally {
                MDC.clear();
            }
        });
    }

    private String requestId(String fromClient) {
        if (fromClient != null && VALID_REQUEST_ID.matcher(fromClient).matches()) {
            return fromClient;
        }
        return UUID.randomUUID().toString();
    }
}
