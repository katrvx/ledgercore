package com.ledgercore.http;

import spark.Response;
import spark.Service;

public class ErrorHandlers {

    public void register(Service http) {
        http.exception(ValidationException.class, (e, req, res) -> send(res, 400, e.getMessage()));
        http.exception(NotFoundException.class, (e, req, res) -> send(res, 404, e.getMessage()));
        http.exception(ConflictException.class, (e, req, res) -> send(res, 409, e.getMessage()));
        http.exception(UnprocessableException.class, (e, req, res) -> send(res, 422, e.getMessage()));
    }

    private void send(Response res, int status, String detail) {
        res.status(status);
        res.type("application/problem+json");
        res.body(Json.write(Problem.of(status, detail)));
    }
}
