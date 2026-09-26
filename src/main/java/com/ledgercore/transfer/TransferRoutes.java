package com.ledgercore.transfer;

import com.ledgercore.http.Json;
import com.ledgercore.http.PathId;
import com.ledgercore.idempotency.IdempotencyService;
import com.ledgercore.idempotency.StoredResponse;
import spark.Request;
import spark.Response;
import spark.Service;

public class TransferRoutes {

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransferRoutes(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    public void register(Service http) {
        http.post("/transfers", this::create);
        http.get("/transfers/:id", this::get);
        // a deposit is a transfer too, so its route lives here
        http.post("/accounts/:id/deposits", this::deposit);
    }

    private String create(Request request, Response response) {
        CreateTransferRequest body = Json.read(request.bodyAsBytes(), CreateTransferRequest.class);
        StoredResponse result = idempotency.run(request, tx -> created(transfers.transfer(tx, body)));
        return reply(response, result);
    }

    private String deposit(Request request, Response response) {
        long accountId = PathId.parse(request.params("id"), "account id");
        DepositRequest body = Json.read(request.bodyAsBytes(), DepositRequest.class);
        StoredResponse result = idempotency.run(request, tx -> created(transfers.deposit(tx, accountId, body)));
        return reply(response, result);
    }

    private String get(Request request, Response response) {
        Transfer transfer = transfers.get(PathId.parse(request.params("id"), "transfer id"));
        response.type("application/json");
        return Json.write(transfer);
    }

    private StoredResponse created(Transfer transfer) {
        return new StoredResponse(201, "/transfers/" + transfer.id(), Json.write(transfer));
    }

    // a replayed response looks exactly like the first one, including the Location header
    private String reply(Response response, StoredResponse stored) {
        response.status(stored.status());
        response.type(stored.status() < 400 ? "application/json" : "application/problem+json");
        if (stored.location() != null) {
            response.header("Location", stored.location());
        }
        return stored.body();
    }
}
