package com.ledgercore;

import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountRoutes;
import com.ledgercore.account.AccountService;
import com.ledgercore.config.AppConfig;
import com.ledgercore.config.Database;
import com.ledgercore.config.Redis;
import com.ledgercore.fraud.FraudEngine;
import com.ledgercore.fraud.FraudFactsCollector;
import com.ledgercore.http.ErrorHandlers;
import com.ledgercore.http.HealthRoutes;
import com.ledgercore.idempotency.IdempotencyCache;
import com.ledgercore.idempotency.IdempotencyRepository;
import com.ledgercore.idempotency.IdempotencyService;
import com.ledgercore.ledger.LedgerRepository;
import com.ledgercore.transfer.TransferRepository;
import com.ledgercore.transfer.TransferRoutes;
import com.ledgercore.transfer.TransferService;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import spark.Service;

import java.time.Clock;

public class App {

    private final Service http;
    private final HikariDataSource dataSource;
    private final Redis redis;

    private App(Service http, HikariDataSource dataSource, Redis redis) {
        this.http = http;
        this.dataSource = dataSource;
        this.redis = redis;
    }

    public static void main(String[] args) {
        start(AppConfig.fromEnv());
    }

    // wires all dependencies by hand and starts the http server
    public static App start(AppConfig config) {
        HikariDataSource dataSource = Database.connect(config);
        DSLContext db = DSL.using(dataSource, SQLDialect.POSTGRES);
        Redis redis = new Redis(config.redisUrl());

        AccountRepository accountRepository = new AccountRepository(db);
        AccountService accountService = new AccountService(accountRepository);
        TransferService transferService = new TransferService(
                accountRepository, new TransferRepository(db), new LedgerRepository(),
                new FraudFactsCollector(redis, config.fraud(), Clock.systemUTC()), FraudEngine.fromConfig(config.fraud()));
        IdempotencyService idempotencyService = new IdempotencyService(
                db, new IdempotencyCache(redis), new IdempotencyRepository(db));

        Service http = Service.ignite().port(config.port());
        new ErrorHandlers().register(http);
        new HealthRoutes(dataSource, redis).register(http);
        new AccountRoutes(accountService).register(http);
        new TransferRoutes(transferService, idempotencyService).register(http);
        http.awaitInitialization();
        return new App(http, dataSource, redis);
    }

    public int port() {
        return http.port();
    }

    public void stop() {
        http.stop();
        http.awaitStop();
        redis.close();
        dataSource.close();
    }
}
