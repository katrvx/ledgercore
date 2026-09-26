package com.ledgercore

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// lets a spec move time forward instead of sleeping
class MutableClock extends Clock {

    Instant now = Instant.now()

    void advance(Duration duration) {
        now = now.plus(duration)
    }

    @Override
    ZoneId getZone() {
        ZoneOffset.UTC
    }

    @Override
    Clock withZone(ZoneId zone) {
        this
    }

    @Override
    Instant instant() {
        now
    }
}
