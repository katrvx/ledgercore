package com.ledgercore.config;

public record AppConfig(int port, String databaseUrl, String databaseUser, String databasePassword, String redisUrl,
                        FraudConfig fraud) {

    public static AppConfig fromEnv() {
        return new AppConfig(
                Env.optionalInt("PORT", 8080),
                Env.required("DATABASE_URL"),
                Env.required("DATABASE_USER"),
                Env.required("DATABASE_PASSWORD"),
                Env.required("REDIS_URL"),
                FraudConfig.fromEnv());
    }

    // keeps the database password and the redis url (it may hold a password) out of logs
    @Override
    public String toString() {
        return "AppConfig[port=" + port + ", databaseUrl=" + databaseUrl + ", databaseUser=" + databaseUser
                + ", fraud=" + fraud + "]";
    }
}
