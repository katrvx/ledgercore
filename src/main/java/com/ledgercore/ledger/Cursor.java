package com.ledgercore.ledger;

import com.ledgercore.http.ValidationException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

// opaque to clients, so they can't build one by hand and the format can change later
public class Cursor {

    public static String encode(long entryId) {
        byte[] bytes = Long.toString(entryId).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static long decode(String cursor) {
        long entryId;
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            entryId = Long.parseLong(text);
        } catch (IllegalArgumentException e) {
            // bad base64 and a bad number both end up here
            throw invalid();
        }
        if (entryId <= 0) {
            throw invalid();
        }
        return entryId;
    }

    private static ValidationException invalid() {
        return new ValidationException("cursor is not valid");
    }
}
