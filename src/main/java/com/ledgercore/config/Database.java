package com.ledgercore.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

public class Database {

    // opens the connection pool and brings the schema up to date
    public static HikariDataSource connect(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.databaseUrl());
        hikari.setUsername(config.databaseUser());
        hikari.setPassword(config.databasePassword());
        // the default is 30 s: too long for a readiness probe, and a queue that long helps nobody
        hikari.setConnectionTimeout(5_000);
        HikariDataSource dataSource = new HikariDataSource(hikari);

        Flyway.configure().dataSource(dataSource).load().migrate();
        return dataSource;
    }
}
