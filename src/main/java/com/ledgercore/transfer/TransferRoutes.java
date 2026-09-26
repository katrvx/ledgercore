package com.ledgercore.transfer;

import com.ledgercore.http.Json;
import com.ledgercore.http.PathId;
import spark.Request;
import spark.Response;
import spark.Service;

public class TransferRoutes {

    private final TransferService transfers;

    public TransferRoutes(TransferService transfers) {
        this.transfers = transfers;
    }

    public void register(Service http) {
        http.post("/transfers", this::create);
        http.get("/transfers/:id", this::get);
        // a deposit is a transfer too, so its route lives here
        http.post("/accounts/:id/deposits", this::deposit);
    }

    private String create(Request request, Response response) {
        CreateTransferRequest body = Json.read(request.bodyAsBytes(), CreateTransferRequest.class);
        return created(response, transfers.transfer(body));
    }

    private String deposit(Request request, Response response) {
        long accountId = PathId.parse(request.params("id"), "account id");
        DepositRequest body = Json.read(request.bodyAsBytes(), DepositRequest.class);
        return created(response, transfers.deposit(accountId, body));
    }

    private String get(Request request, Response response) {
        Transfer transfer = transfers.get(PathId.parse(request.params("id"), "transfer id"));
        response.type("application/json");
        return Json.write(transfer);
    }

    private String created(Response response, Transfer transfer) {
        response.status(201);
        response.type("application/json");
        response.header("Location", "/transfers/" + transfer.id());
        return Json.write(transfer);
    }
}
