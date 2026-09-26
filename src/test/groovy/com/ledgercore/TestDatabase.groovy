package com.ledgercore

import com.ledgercore.config.AppConfig
import org.testcontainers.postgresql.PostgreSQLContainer

// one postgres container shared by all specs in a test run
class TestDatabase {

    static final PostgreSQLContainer POSTGRES = startPostgres()

    static AppConfig appConfig() {
        new AppConfig(0, POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
    }

    private static PostgreSQLContainer startPostgres() {
        def container = new PostgreSQLContainer("postgres:18-alpine")
        container.start()
        container
    }
}
