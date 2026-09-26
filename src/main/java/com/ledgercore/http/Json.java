package com.ledgercore.http;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

public class Json {

    // strict on purpose: money fields must be whole numbers, not "100" or 100.5
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    // bad json from a client becomes a 400, not a 500
    public static <T> T read(byte[] body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (UnrecognizedPropertyException e) {
            throw new ValidationException("unknown field: " + e.getPropertyName());
        } catch (MismatchedInputException e) {
            if (e.getPath().isEmpty()) {
                throw new ValidationException("request body is not valid json");
            }
            throw new ValidationException("invalid value for field " + e.getPath().getLast().getPropertyName());
        } catch (JacksonException e) {
            throw new ValidationException("request body is not valid json");
        }
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
