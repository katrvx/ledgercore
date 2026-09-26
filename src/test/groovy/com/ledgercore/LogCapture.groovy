package com.ledgercore

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

// collects every log event while it is open, so a spec can check what was logged
class LogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>()
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)

    LogCapture() {
        appender.start()
        root.addAppender(appender)
    }

    List<ILoggingEvent> events() {
        // the appender adds under its own lock, so copy under the same lock
        synchronized (appender) {
            new ArrayList<>(appender.list)
        }
    }

    List<ILoggingEvent> events(String loggerName) {
        events().findAll { it.loggerName == loggerName }
    }

    @Override
    void close() {
        root.detachAppender(appender)
        appender.stop()
    }

    static void waitUntil(Closure<Boolean> condition) {
        for (int i = 0; i < 50; i++) {
            if (condition()) {
                return
            }
            Thread.sleep(100)
        }
        throw new AssertionError("condition was not met in 5 seconds")
    }
}
