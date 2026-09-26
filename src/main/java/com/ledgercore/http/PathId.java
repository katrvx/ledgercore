package com.ledgercore.http;

public class PathId {

    // ids in the path must be positive longs, anything else is a client error
    public static long parse(String value, String name) {
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ValidationException(name + " must be a positive number");
        }
        if (id <= 0) {
            throw new ValidationException(name + " must be a positive number");
        }
        return id;
    }
}
