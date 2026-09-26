package com.ledgercore.http;

import spark.Response;
import spark.Service;

public class ErrorHandlers {

    public void register(Service http) {
        http.exception(ValidationException.class, (e, req, res) -> send(res, 400, "Bad Request", e.getMessage()));
        http.exception(NotFoundException.class, (e, req, res) -> send(res, 404, "Not Found", e.getMessage()));
    }

    private void send(Response res, int status, String title, String detail) {
        res.status(status);
        res.type("application/problem+json");
        res.body(Json.write(new Problem("about:blank", title, status, detail)));
    }
}
