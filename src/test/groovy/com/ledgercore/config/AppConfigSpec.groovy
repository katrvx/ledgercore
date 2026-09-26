package com.ledgercore.config

import spock.lang.Specification

import java.time.Duration

class AppConfigSpec extends Specification {

    def "toString does not show the database password or the redis url"() {
        given:
        def config = new AppConfig(8080, "jdbc:postgresql://db:5432/ledgercore", "ledgercore", "s3cret",
                "redis://:r3dis@cache:6379", FraudConfig.defaults())

        expect:
        !config.toString().contains("s3cret")
        !config.toString().contains("r3dis")
        config.toString().contains("jdbc:postgresql://db:5432/ledgercore")
    }

    def "fraud defaults match the documented thresholds"() {
        expect:
        FraudConfig.defaults() == new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 5, 20, 100_000)
    }
}
