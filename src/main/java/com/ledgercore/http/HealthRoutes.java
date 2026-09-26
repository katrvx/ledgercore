package com.ledgercore.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class HealthRoutes {

    private static final Logger log = LoggerFactory.getLogger(HealthRoutes.class);

    private final DataSource dataSource;

    public HealthRoutes(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void register(Service http) {
        // liveness only, it must not depend on the database or redis
        http.get("/health", (req, res) -> {
            res.type("application/json");
            return "{\"status\":\"UP\"}";
        });

        http.get("/ready", (req, res) -> {
            res.type("application/json");
            if (databaseIsUp()) {
                return "{\"status\":\"UP\"}";
            }
            res.status(503);
            return "{\"status\":\"DOWN\"}";
        });
    }

    private boolean databaseIsUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException e) {
            log.warn("database is not reachable: {}", e.getMessage());
            return false;
        }
    }
}
