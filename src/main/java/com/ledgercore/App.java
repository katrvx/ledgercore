package com.ledgercore;

import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountRoutes;
import com.ledgercore.account.AccountService;
import com.ledgercore.config.AppConfig;
import com.ledgercore.config.Database;
import com.ledgercore.http.ErrorHandlers;
import com.ledgercore.http.HealthRoutes;
import com.ledgercore.ledger.LedgerRepository;
import com.ledgercore.transfer.TransferRepository;
import com.ledgercore.transfer.TransferRoutes;
import com.ledgercore.transfer.TransferService;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import spark.Service;

public class App {

    private final Service http;
    private final HikariDataSource dataSource;

    private App(Service http, HikariDataSource dataSource) {
        this.http = http;
        this.dataSource = dataSource;
    }

    public static void main(String[] args) {
        start(AppConfig.fromEnv());
    }

    // wires all dependencies by hand and starts the http server
    public static App start(AppConfig config) {
        HikariDataSource dataSource = Database.connect(config);
        DSLContext db = DSL.using(dataSource, SQLDialect.POSTGRES);

        AccountRepository accountRepository = new AccountRepository(db);
        AccountService accountService = new AccountService(accountRepository);
        TransferService transferService = new TransferService(
                db, accountRepository, new TransferRepository(db), new LedgerRepository());

        Service http = Service.ignite().port(config.port());
        new ErrorHandlers().register(http);
        new HealthRoutes(dataSource).register(http);
        new AccountRoutes(accountService).register(http);
        new TransferRoutes(transferService).register(http);
        http.awaitInitialization();
        return new App(http, dataSource);
    }

    public int port() {
        return http.port();
    }

    public void stop() {
        http.stop();
        http.awaitStop();
        dataSource.close();
    }
}
