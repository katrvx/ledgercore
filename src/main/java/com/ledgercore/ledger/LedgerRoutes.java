package com.ledgercore.ledger;

import com.ledgercore.http.Json;
import com.ledgercore.http.PathId;
import spark.Service;

public class LedgerRoutes {

    private final LedgerService ledger;

    public LedgerRoutes(LedgerService ledger) {
        this.ledger = ledger;
    }

    public void register(Service http) {
        http.get("/accounts/:id/transactions", (req, res) -> {
            long accountId = PathId.parse(req.params("id"), "account id");
            TransactionPage page = ledger.history(accountId, req.queryParams("limit"), req.queryParams("cursor"));
            res.type("application/json");
            return Json.write(page);
        });
    }
}
