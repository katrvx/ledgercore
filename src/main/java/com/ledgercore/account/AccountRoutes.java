package com.ledgercore.account;

import com.ledgercore.http.Json;
import com.ledgercore.http.ValidationException;
import spark.Request;
import spark.Response;
import spark.Service;

public class AccountRoutes {

    private final AccountService accounts;

    public AccountRoutes(AccountService accounts) {
        this.accounts = accounts;
    }

    public void register(Service http) {
        http.post("/accounts", this::create);
        http.get("/accounts/:id", this::get);
    }

    private String create(Request request, Response response) {
        CreateAccountRequest body = Json.read(request.bodyAsBytes(), CreateAccountRequest.class);
        Account account = accounts.create(body);
        response.status(201);
        response.type("application/json");
        response.header("Location", "/accounts/" + account.id());
        return Json.write(account);
    }

    private String get(Request request, Response response) {
        Account account = accounts.get(parseId(request.params("id")));
        response.type("application/json");
        return Json.write(account);
    }

    private long parseId(String value) {
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ValidationException("account id must be a positive number");
        }
        if (id <= 0) {
            throw new ValidationException("account id must be a positive number");
        }
        return id;
    }
}
