package com.ledgercore

import com.ledgercore.config.AppConfig
import com.ledgercore.config.FraudConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

import java.time.Duration

// one postgres and one redis container shared by all specs in a test run
class TestEnv {

    static final PostgreSQLContainer POSTGRES = startPostgres()
    static final GenericContainer REDIS = startRedis()

    // for specs that test locking or idempotency under load, not fraud
    static final FraudConfig NO_FRAUD_LIMITS = new FraudConfig(
            Integer.MAX_VALUE, Duration.ofSeconds(1), Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, 20, Long.MAX_VALUE)

    static AppConfig appConfig() {
        appConfig(redisUrl(), FraudConfig.defaults())
    }

    static AppConfig appConfig(String redisUrl) {
        appConfig(redisUrl, FraudConfig.defaults())
    }

    static AppConfig appConfig(FraudConfig fraud) {
        appConfig(redisUrl(), fraud)
    }

    static AppConfig appConfig(String redisUrl, FraudConfig fraud) {
        new AppConfig(0, POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password, redisUrl, fraud)
    }

    static String redisUrl() {
        "redis://${REDIS.host}:${REDIS.getMappedPort(6379)}"
    }

    private static PostgreSQLContainer startPostgres() {
        def container = new PostgreSQLContainer("postgres:18-alpine")
        container.start()
        container
    }

    private static GenericContainer startRedis() {
        def container = new GenericContainer("redis:7-alpine").withExposedPorts(6379)
        container.start()
        container
    }
}
