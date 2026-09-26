package com.ledgercore.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Response;
import spark.Service;

public class ErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlers.class);

    public void register(Service http) {
        http.exception(ValidationException.class, (e, req, res) -> send(res, 400, e.getMessage()));
        http.exception(NotFoundException.class, (e, req, res) -> send(res, 404, e.getMessage()));
        http.exception(ConflictException.class, (e, req, res) -> send(res, 409, e.getMessage()));
        http.exception(PayloadTooLargeException.class, (e, req, res) -> send(res, 413, e.getMessage()));
        http.exception(UnsupportedMediaTypeException.class, (e, req, res) -> send(res, 415, e.getMessage()));
        http.exception(UnprocessableException.class, (e, req, res) -> send(res, 422, e.getMessage()));

        // anything else is a bug on our side: the log gets the details, the client only gets the request id
        http.exception(Exception.class, (e, req, res) -> {
            log.error("unhandled error on {} {}", req.requestMethod(), req.pathInfo(), e);
            send(res, 500, "internal error");
        });

        // spark answers a wrong method with 404 too, it can't tell the two apart
        http.notFound((req, res) -> {
            res.type("application/problem+json");
            return Json.write(Problem.forCurrentRequest(404, "no route for " + req.requestMethod() + " " + req.pathInfo()));
        });
    }

    private void send(Response res, int status, String detail) {
        res.status(status);
        res.type("application/problem+json");
        res.body(Json.write(Problem.forCurrentRequest(status, detail)));
    }
}
