package com.ledgercore.http;

import spark.Service;

public class HealthRoutes {

    // liveness only, it must not depend on the database or redis
    public void register(Service http) {
        http.get("/health", (req, res) -> {
            res.type("application/json");
            return "{\"status\":\"UP\"}";
        });
    }
}
