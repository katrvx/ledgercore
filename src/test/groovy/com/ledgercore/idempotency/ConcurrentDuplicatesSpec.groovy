package com.ledgercore.idempotency

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

class ConcurrentDuplicatesSpec extends Specification {

    static final int DUPLICATES = 10

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

    def "10 identical requests at once move money once"() {
        given:
        def alice = createAccount()
        def bob = createAccount()
        deposit(alice, 1000)
        def key = UUID.randomUUID().toString()
        def body = """{"fromAccountId":$alice,"toAccountId":$bob,"amount":100,"currency":"EUR"}"""
        def pool = Executors.newFixedThreadPool(DUPLICATES)

        when:
        def futures = (1..DUPLICATES).collect {
            pool.submit({ client.post("/transfers", body, key) } as Callable)
        }
        def responses = futures*.get()
        def statuses = responses*.statusCode()
        def bodies = responses.findAll { it.statusCode() == 201 }*.body() as Set

        then:
        // a duplicate either arrives while the first runs (409) or after it finished (replayed 201)
        statuses.findAll { it != 201 && it != 409 } == []
        statuses.count { it == 201 } >= 1
        bodies.size() == 1

        and:
        balance(alice) == 900
        balance(bob) == 100
        transferCount(alice) == 1

        cleanup:
        pool.shutdown()
        println "concurrent duplicates: ${statuses.count { it == 201 }} replayed or completed, ${statuses.count { it == 409 }} in progress"
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

    private long balance(long accountId) {
        json.parseText(client.get("/accounts/$accountId").body()).balance as long
    }

    private long transferCount(long accountId) {
        sql.firstRow("select count(*) as n from transfers where from_account_id = ?", [accountId]).n as long
    }
}
