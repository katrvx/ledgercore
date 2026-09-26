package com.ledgercore.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import groovy.json.JsonSlurper
import spock.lang.Specification

class JsonLogLayoutSpec extends Specification {

    def layout = new JsonLogLayout()
    def logger = new LoggerContext().getLogger("com.ledgercore.Test")

    def "a log line is one json object with the fields cloud logging reads"() {
        given:
        def event = event(Level.WARN, 'saved "quotes"\nand a newline', null, [requestId: "req-1"])

        when:
        def line = layout.doLayout(event)
        def parsed = new JsonSlurper().parseText(line)

        then:
        line.endsWith("\n")
        line.count("\n") == 1
        parsed.severity == "WARNING"
        parsed.message == 'saved "quotes"\nand a newline'
        parsed.logger == "com.ledgercore.Test"
        parsed.requestId == "req-1"
        parsed.time == "2026-09-26T10:00:00Z"
        !parsed.containsKey("stack_trace")
    }

    def "severity #level is written as #severity"() {
        expect:
        new JsonSlurper().parseText(layout.doLayout(event(level, "m", null, [:]))).severity == severity

        where:
        level       || severity
        Level.TRACE || "DEBUG"
        Level.DEBUG || "DEBUG"
        Level.INFO  || "INFO"
        Level.WARN  || "WARNING"
        Level.ERROR || "ERROR"
    }

    def "an exception goes into stack_trace and a missing request id is left out"() {
        given:
        def event = event(Level.ERROR, "failed", new IllegalStateException("boom"), [:])

        when:
        def parsed = new JsonSlurper().parseText(layout.doLayout(event))

        then:
        parsed.stack_trace.startsWith("java.lang.IllegalStateException: boom")
        parsed.stack_trace.contains("at ")
        !parsed.containsKey("requestId")
    }

    private LoggingEvent event(Level level, String message, Throwable error, Map<String, String> mdc) {
        def event = new LoggingEvent(Logger.name, logger, level, message, error, null)
        event.timeStamp = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli()
        event.MDCPropertyMap = mdc
        event
    }
}
