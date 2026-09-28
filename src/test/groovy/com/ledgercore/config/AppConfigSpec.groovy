package com.ledgercore.config

import spock.lang.Specification

import java.time.Duration

class AppConfigSpec extends Specification {

    static final Map<String, String> REQUIRED = [
            DATABASE_URL     : "jdbc:postgresql://db:5432/ledgercore",
            DATABASE_USER    : "ledgercore",
            DATABASE_PASSWORD: "s3cret"]

    def "toString does not show the database password or the redis url"() {
        given:
        def config = AppConfig.from(REQUIRED + [REDIS_URL: "redis://:r3dis@cache:6379"])

        expect:
        !config.toString().contains("s3cret")
        !config.toString().contains("r3dis")
        config.toString().contains("jdbc:postgresql://db:5432/ledgercore")
    }

    def "only the database settings are required, the rest has defaults"() {
        when:
        def config = AppConfig.from(REQUIRED)

        then:
        config.port() == 8080
        config.databasePoolSize() == 10
        config.redisUrl() == null
        config.redisTimeout() == Duration.ofMillis(500)
        config.fraud() == FraudConfig.defaults()
    }

    def "every setting can come from the environment"() {
        when:
        def config = AppConfig.from(REQUIRED + [
                PORT                        : "9000",
                DATABASE_POOL_SIZE          : "4",
                REDIS_URL                   : "redis://cache:6379",
                REDIS_TIMEOUT_MS            : "250",
                FRAUD_VELOCITY_MAX_TRANSFERS: "7",
                FRAUD_NEW_RECIPIENT_LIMIT   : "5000"])

        then:
        config.port() == 9000
        config.databasePoolSize() == 4
        config.redisUrl() == "redis://cache:6379"
        config.redisTimeout() == Duration.ofMillis(250)
        config.fraud().velocityMaxTransfers() == 7
        config.fraud().newRecipientLimit() == 5000
        config.fraud().absoluteLimit() == FraudConfig.defaults().absoluteLimit()
    }

    def "a missing #name stops the start with a clear message"() {
        when:
        AppConfig.from(REQUIRED.findAll { it.key != name })

        then:
        def e = thrown(IllegalStateException)
        e.message == "environment variable $name is not set"

        where:
        name << ["DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD"]
    }

    def "#name set to '#value' stops the start and names the variable"() {
        when:
        AppConfig.from(REQUIRED + [(name): value])

        then:
        def e = thrown(IllegalStateException)
        e.message == "environment variable $name must be a positive whole number, got '$value'"

        where:
        name                            | value
        "PORT"                          | "abc"
        "PORT"                          | "0"
        "DATABASE_POOL_SIZE"            | "-1"
        "REDIS_TIMEOUT_MS"              | "1.5"
        "FRAUD_VELOCITY_MAX_TRANSFERS"  | "0"
        "FRAUD_VELOCITY_WINDOW_SECONDS" | "-60"
        "FRAUD_ABSOLUTE_LIMIT"          | "ten"
        "FRAUD_ANOMALY_MULTIPLIER"      | "0"
        "FRAUD_NEW_RECIPIENT_LIMIT"     | "99999999999999999999"
    }

    def "fraud defaults match the documented thresholds"() {
        expect:
        FraudConfig.defaults() == new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 5, 20, 100_000)
    }

    def "a fraud config with a limit that is not positive can't be built"() {
        when:
        new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 5, 20, -1)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "newRecipientLimit must be positive"
    }
}
