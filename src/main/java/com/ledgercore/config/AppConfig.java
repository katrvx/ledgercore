package com.ledgercore.config;

public record AppConfig(int port, String databaseUrl, String databaseUser, String databasePassword, String redisUrl) {

    public static AppConfig fromEnv() {
        return new AppConfig(
                Integer.parseInt(optional("PORT", "8080")),
                required("DATABASE_URL"),
                required("DATABASE_USER"),
                required("DATABASE_PASSWORD"),
                required("REDIS_URL"));
    }

    // keeps the database password and the redis url (it may hold a password) out of logs
    @Override
    public String toString() {
        return "AppConfig[port=" + port + ", databaseUrl=" + databaseUrl + ", databaseUser=" + databaseUser + "]";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is not set");
        }
        return value;
    }

    private static String optional(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
