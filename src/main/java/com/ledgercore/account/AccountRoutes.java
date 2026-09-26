package com.ledgercore.account;

import com.ledgercore.http.Json;
import com.ledgercore.http.PathId;
import com.ledgercore.http.RequestBody;
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
        CreateAccountRequest body = Json.read(RequestBody.read(request), CreateAccountRequest.class);
        Account account = accounts.create(body);
        response.status(201);
        response.type("application/json");
        response.header("Location", "/accounts/" + account.id());
        return Json.write(account);
    }

    private String get(Request request, Response response) {
        Account account = accounts.get(PathId.parse(request.params("id"), "account id"));
        response.type("application/json");
        return Json.write(account);
    }
}
