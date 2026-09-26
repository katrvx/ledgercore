package com.ledgercore.transfer;

import com.ledgercore.http.Json;
import com.ledgercore.http.PathId;
import com.ledgercore.http.Problem;
import com.ledgercore.http.RequestBody;
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
        CreateTransferRequest body = Json.read(RequestBody.read(request), CreateTransferRequest.class);
        StoredResponse result = idempotency.run(request, tx -> toResponse(transfers.transfer(tx, body)));
        return reply(response, result);
    }

    private String deposit(Request request, Response response) {
        long accountId = PathId.parse(request.params("id"), "account id");
        DepositRequest body = Json.read(RequestBody.read(request), DepositRequest.class);
        StoredResponse result = idempotency.run(request, tx -> toResponse(transfers.deposit(tx, accountId, body)));
        return reply(response, result);
    }

    private String get(Request request, Response response) {
        Transfer transfer = transfers.get(PathId.parse(request.params("id"), "transfer id"));
        response.type("application/json");
        return Json.write(transfer);
    }

    private StoredResponse toResponse(Transfer transfer) {
        String location = "/transfers/" + transfer.id();
        return switch (transfer.status()) {
            case COMPLETED -> new StoredResponse(201, location, Json.write(transfer));
            // accepted but not done: no money moves until someone reviews it
            case PENDING_REVIEW -> new StoredResponse(202, location, Json.write(transfer));
            // the declined row stays in the database for audit, the client only learns that it was declined
            case DECLINED -> new StoredResponse(422, null, Json.write(Problem.of(422, "transfer was declined by risk checks")));
        };
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
