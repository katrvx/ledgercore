package com.ledgercore.http;

import com.ledgercore.config.Redis;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class HealthRoutes {

    private static final Logger log = LoggerFactory.getLogger(HealthRoutes.class);

    private final DataSource dataSource;
    private final Redis redis;

    public HealthRoutes(DataSource dataSource, Redis redis) {
        this.dataSource = dataSource;
        this.redis = redis;
    }

    private record Readiness(String status, String database, String redis) {
    }

    public void register(Service http) {
        // liveness only, it must not depend on the database or redis
        http.get("/health", (req, res) -> {
            res.type("application/json");
            return "{\"status\":\"UP\"}";
        });

        // without redis the app still works, only slower, so only the database decides
        http.get("/ready", (req, res) -> {
            boolean databaseUp = databaseIsUp();
            res.type("application/json");
            if (!databaseUp) {
                res.status(503);
            }
            return Json.write(new Readiness(upOrDown(databaseUp), upOrDown(databaseUp), redisState()));
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

    // not configured is a setting, not an outage, so it gets its own word
    private String redisState() {
        if (!redis.isEnabled()) {
            return "DISABLED";
        }
        return upOrDown(redisIsUp());
    }

    private boolean redisIsUp() {
        try {
            return "PONG".equals(redis.call(commands -> commands.ping()));
        } catch (RedisException e) {
            return false;
        }
    }

    private String upOrDown(boolean up) {
        return up ? "UP" : "DOWN";
    }
}
