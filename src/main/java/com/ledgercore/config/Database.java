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
        HikariDataSource dataSource = new HikariDataSource(hikari);

        Flyway.configure().dataSource(dataSource).load().migrate();
        return dataSource;
    }
}
