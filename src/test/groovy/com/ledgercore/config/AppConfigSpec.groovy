package com.ledgercore.config

import spock.lang.Specification

class AppConfigSpec extends Specification {

    def "toString does not show the database password or the redis url"() {
        given:
        def config = new AppConfig(8080, "jdbc:postgresql://db:5432/ledgercore", "ledgercore", "s3cret",
                "redis://:r3dis@cache:6379")

        expect:
        !config.toString().contains("s3cret")
        !config.toString().contains("r3dis")
        config.toString().contains("jdbc:postgresql://db:5432/ledgercore")
    }
}
