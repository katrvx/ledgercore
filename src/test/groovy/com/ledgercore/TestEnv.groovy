package com.ledgercore

import com.ledgercore.config.AppConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

// one postgres and one redis container shared by all specs in a test run
class TestEnv {

    static final PostgreSQLContainer POSTGRES = startPostgres()
    static final GenericContainer REDIS = startRedis()

    static AppConfig appConfig() {
        appConfig(redisUrl())
    }

    static AppConfig appConfig(String redisUrl) {
        new AppConfig(0, POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password, redisUrl)
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
