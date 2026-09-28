package com.ledgercore.http;

import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

public class ErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlers.class);

    public void register(Service http) {
        http.exception(ValidationException.class, (e, req, res) -> send(res, 400, e.getMessage()));
        http.exception(NotFoundException.class, (e, req, res) -> send(res, 404, e.getMessage()));
        http.exception(ConflictException.class, (e, req, res) -> {
            // the first request with this key is usually done within a second
            res.header("Retry-After", "1");
            send(res, 409, e.getMessage());
        });
        http.exception(PayloadTooLargeException.class, (e, req, res) -> send(res, 413, e.getMessage()));
        http.exception(UnsupportedMediaTypeException.class, (e, req, res) -> send(res, 415, e.getMessage()));
        http.exception(UnprocessableException.class, (e, req, res) -> send(res, 422, e.getMessage()));

        // no connection to the database is an outage, not a bug: the client should retry and the log stays short
        http.exception(DataAccessException.class, (e, req, res) -> {
            if (databaseIsUnavailable(e)) {
                log.warn("database is not available on {} {}: {}", req.requestMethod(), req.pathInfo(), e.getMessage());
                res.header("Retry-After", "1");
                send(res, 503, "the database is not available, try again");
            } else {
                unexpected(e, req, res);
            }
        });

        http.exception(Exception.class, this::unexpected);

        // spark answers a wrong method with 404 too, it can't tell the two apart
        http.notFound((req, res) -> {
            res.type("application/problem+json");
            return Json.write(Problem.forCurrentRequest(404, "no route for " + req.requestMethod() + " " + req.pathInfo()));
        });
    }

    // anything else is a bug on our side: the log gets the details, the client only gets the request id
    private void unexpected(Exception e, Request req, Response res) {
        log.error("unhandled error on {} {}", req.requestMethod(), req.pathInfo(), e);
        send(res, 500, "internal error");
    }

    // the pool gave up waiting for a connection, or postgres closed one
    private boolean databaseIsUnavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sql && connectionIsGone(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // sql state class 08 is a broken connection, 57P is postgres shutting down or crashing
    private boolean connectionIsGone(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("57P"));
    }

    private void send(Response res, int status, String detail) {
        res.status(status);
        res.type("application/problem+json");
        res.body(Json.write(Problem.forCurrentRequest(status, detail)));
    }
}
