package com.ledgercore;

import com.ledgercore.http.HealthRoutes;
import spark.Service;

public class App {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        start(port);
    }

    // wires all dependencies by hand and starts the http server
    public static Service start(int port) {
        Service http = Service.ignite().port(port);
        new HealthRoutes().register(http);
        http.awaitInitialization();
        return http;
    }
}
