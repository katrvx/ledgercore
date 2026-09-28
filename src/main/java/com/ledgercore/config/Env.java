package com.ledgercore.config;

import java.util.Map;

// all configuration comes from environment variables, passed in as a map so specs can use their own
public class Env {

    private final Map<String, String> values;

    public Env(Map<String, String> values) {
        this.values = values;
    }

    public String required(String name) {
        String value = optional(name, null);
        if (value == null) {
            throw new IllegalStateException("environment variable " + name + " is not set");
        }
        return value;
    }

    public String optional(String name, String defaultValue) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    public int positiveInt(String name, int defaultValue) {
        return (int) positive(name, defaultValue, Integer.MAX_VALUE);
    }

    public long positiveLong(String name, long defaultValue) {
        return positive(name, defaultValue, Long.MAX_VALUE);
    }

    // a wrong setting should stop the start with the variable's name, not show up later as odd behaviour
    private long positive(String name, long defaultValue, long max) {
        String value = optional(name, null);
        if (value == null) {
            return defaultValue;
        }
        long number = parseOrZero(value);
        if (number <= 0 || number > max) {
            throw new IllegalStateException(
                    "environment variable " + name + " must be a positive whole number, got '" + value + "'");
        }
        return number;
    }

    private long parseOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
