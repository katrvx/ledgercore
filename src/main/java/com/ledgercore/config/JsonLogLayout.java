package com.ledgercore.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// one json object per line, with the field names google cloud logging reads: severity, message, time
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("severity", severity(event.getLevel()));
        line.put("logger", event.getLoggerName());
        line.put("message", event.getFormattedMessage());
        String requestId = event.getMDCPropertyMap().get("requestId");
        if (requestId != null) {
            line.put("requestId", requestId);
        }
        if (event.getThrowableProxy() != null) {
            line.put("stack_trace", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return MAPPER.writeValueAsString(line) + CoreConstants.LINE_SEPARATOR;
    }

    // cloud logging says WARNING, not WARN
    private String severity(Level level) {
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return "ERROR";
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            return "WARNING";
        }
        if (level.isGreaterOrEqual(Level.INFO)) {
            return "INFO";
        }
        return "DEBUG";
    }
}
