package com.ledgercore.http;

import spark.Request;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class RequestBody {

    public static final int MAX_BYTES = 16 * 1024;

    private static final String CACHED = "ledgercore.body";

    // reads the json body once, and stops at MAX_BYTES + 1, so a huge body is never held in memory
    public static byte[] read(Request request) {
        byte[] cached = request.attribute(CACHED);
        if (cached != null) {
            return cached;
        }
        requireJson(request.contentType());
        if (request.raw().getContentLengthLong() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] body = readAtMost(request.raw(), MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw tooLarge();
        }
        request.attribute(CACHED, body);
        return body;
    }

    // parameters and case don't matter, "Application/JSON; charset=utf-8" is fine
    private static void requireJson(String contentType) {
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!mediaType.equalsIgnoreCase("application/json")) {
            throw new UnsupportedMediaTypeException("Content-Type must be application/json");
        }
    }

    // reads the jetty request under spark's wrapper, which would copy a whole "gzip, chunked" body into memory
    private static byte[] readAtMost(HttpServletRequest raw, int limit) {
        try {
            InputStream in;
            if (raw instanceof HttpServletRequestWrapper wrapper) {
                in = wrapper.getRequest().getInputStream();
            } else {
                in = raw.getInputStream();
            }
            return readUpTo(in, limit);
        } catch (IOException e) {
            throw new ValidationException("request body could not be read");
        }
    }

    // not readNBytes: at the end it asks for 0 more bytes, and jetty blocks on that until the client sends something
    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        int total = 0;
        while (total < limit) {
            int read = in.read(buffer, total, limit - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return Arrays.copyOf(buffer, total);
    }

    private static PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException("request body must be at most " + MAX_BYTES + " bytes");
    }
}
