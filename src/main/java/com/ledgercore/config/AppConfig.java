package com.ledgercore.config;

import java.time.Duration;
import java.util.Map;

public record AppConfig(int port, String databaseUrl, String databaseUser, String databasePassword, int databasePoolSize,
                        String redisUrl, Duration redisTimeout, FraudConfig fraud) {

    public static AppConfig from(Map<String, String> environment) {
        Env env = new Env(environment);
        return new AppConfig(
                env.positiveInt("PORT", 8080),
                env.required("DATABASE_URL"),
                env.required("DATABASE_USER"),
                env.required("DATABASE_PASSWORD"),
                // every instance takes this many connections, all instances together must fit the database's limit
                env.positiveInt("DATABASE_POOL_SIZE", 10),
                // without redis the service still works, idempotency and velocity then use the database
                env.optional("REDIS_URL", null),
                Duration.ofMillis(env.positiveLong("REDIS_TIMEOUT_MS", 500)),
                FraudConfig.from(env));
    }

    // keeps the database password and the redis url (it may hold a password) out of logs
    @Override
    public String toString() {
        return "AppConfig[port=" + port + ", databaseUrl=" + databaseUrl + ", databaseUser=" + databaseUser
                + ", databasePoolSize=" + databasePoolSize + ", redisTimeout=" + redisTimeout + ", fraud=" + fraud + "]";
    }
}
