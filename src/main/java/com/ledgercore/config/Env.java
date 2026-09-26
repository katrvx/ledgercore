package com.ledgercore.config;

// all configuration comes from environment variables
public class Env {

    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is not set");
        }
        return value;
    }

    public static String optional(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    public static int optionalInt(String name, int defaultValue) {
        return Integer.parseInt(optional(name, String.valueOf(defaultValue)));
    }

    public static long optionalLong(String name, long defaultValue) {
        return Long.parseLong(optional(name, String.valueOf(defaultValue)));
    }
}
