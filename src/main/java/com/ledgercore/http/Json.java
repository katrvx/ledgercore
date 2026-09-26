package com.ledgercore.http;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

public class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // bad json from a client becomes a 400, not a 500
    public static <T> T read(byte[] body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (UnrecognizedPropertyException e) {
            throw new ValidationException("unknown field: " + e.getPropertyName());
        } catch (JacksonException e) {
            throw new ValidationException("request body is not valid json");
        }
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
