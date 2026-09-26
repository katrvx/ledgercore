package com.ledgercore.transfer

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.json.JsonSlurper
import groovy.sql.Sql
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrentTransfersSpec extends Specification {

    static final int ACCOUNTS = 10
    static final long START_BALANCE = 300
    static final int TRANSFERS = 1000
    static final int THREADS = 50

    @Shared
    App app

    @Shared
    TestClient client

    @Shared
    HikariDataSource dataSource

    @Shared
    Sql sql

    @Shared
    JsonSlurper json = new JsonSlurper()

    def setupSpec() {
        app = App.start(TestEnv.appConfig())
        client = new TestClient(app.port())
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
        app.stop()
        dataSource.close()
    }

    def "1000 parallel transfers across 10 accounts do not create or lose money"() {
        given:
        def ids = (1..ACCOUNTS).collect { createAccount() }
        ids.each { deposit(it, START_BALANCE) }
        def requests = opposingPairs(ids)
        def pool = Executors.newFixedThreadPool(THREADS)

        when:
        def futures = requests.collect { body ->
            pool.submit({ client.post("/transfers", body) } as Callable)
        }
        def responses = futures*.get()
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        def statuses = responses*.statusCode()

        then:
        // insufficient funds must be the only reason a transfer fails, never a 500 or a deadlock
        statuses.findAll { it != 201 && it != 422 } == []
        statuses.count { it == 201 } > 0
        statuses.count { it == 422 } > 0
        responses.findAll { it.statusCode() == 422 }.every {
            json.parseText(it.body()).detail.endsWith("has insufficient funds")
        }

        and:
        sumOfBalances(ids) == ACCOUNTS * START_BALANCE
        minBalance(ids) >= 0
        completedTransfers(ids) == statuses.count { it == 201 }
        ledgerEntries(ids) == 2 * statuses.count { it == 201 }
        ledgerSum("EUR") == 0

        cleanup:
        println "concurrent transfers: ${statuses.count { it == 201 }} completed, ${statuses.count { it == 422 }} rejected"
    }

    // every pair goes in both directions back to back, so opposite transfers run at the same time
    private static List<String> opposingPairs(List<Long> ids) {
        def random = new Random(42)
        def requests = []
        (TRANSFERS / 2).times {
            def a = ids[random.nextInt(ids.size())]
            def b = ids[random.nextInt(ids.size())]
            while (b == a) {
                b = ids[random.nextInt(ids.size())]
            }
            requests << transfer(a, b, 1 + random.nextInt(500))
            requests << transfer(b, a, 1 + random.nextInt(500))
        }
        requests
    }

    private static String transfer(long from, long to, long amount) {
        """{"fromAccountId":$from,"toAccountId":$to,"amount":$amount,"currency":"EUR"}"""
    }

    private long createAccount() {
        def response = client.post("/accounts", '{"ownerName":"test","currency":"EUR"}')
        assert response.statusCode() == 201
        json.parseText(response.body()).id as long
    }

    private void deposit(long accountId, long amount) {
        def response = client.post("/accounts/$accountId/deposits", """{"amount":$amount}""")
        assert response.statusCode() == 201
    }

    private long sumOfBalances(List<Long> ids) {
        sql.firstRow("select sum(balance) as total from accounts where id in (${placeholders(ids)})", ids).total as long
    }

    private long minBalance(List<Long> ids) {
        sql.firstRow("select min(balance) as lowest from accounts where id in (${placeholders(ids)})", ids).lowest as long
    }

    private long completedTransfers(List<Long> ids) {
        sql.firstRow("select count(*) as n from transfers where status = 'COMPLETED' and from_account_id in (${placeholders(ids)})",
                ids).n as long
    }

    private long ledgerEntries(List<Long> ids) {
        sql.firstRow("""select count(*) as n from ledger_entries e
                join transfers t on t.id = e.transfer_id
                where t.from_account_id in (${placeholders(ids)})""", ids).n as long
    }

    private long ledgerSum(String currency) {
        sql.firstRow("select coalesce(sum(amount), 0) as total from ledger_entries where currency = ?", [currency]).total as long
    }

    private static String placeholders(List<Long> ids) {
        ids.collect { "?" }.join(",")
    }
}
