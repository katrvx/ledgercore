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

class DepositApiSpec extends Specification {

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

    long alice
    long funding

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

    def setup() {
        alice = createAccount("EUR")
        funding = sql.firstRow("select id from accounts where type = 'SYSTEM' and currency = 'EUR'").id as long
    }

    def "deposit is a transfer from the funding account and returns 201"() {
        given:
        def fundingBefore = balance(funding)

        when:
        def response = client.post("/accounts/$alice/deposits", '{"amount":5000}')
        def body = json.parseText(response.body())

        then:
        response.statusCode() == 201
        response.headers().firstValue("Location").get() == "/transfers/${body.id}"
        body.fromAccountId == funding
        body.toAccountId == alice
        body.amount == 5000
        body.currency == "EUR"
        body.status == "COMPLETED"

        and:
        balance(alice) == 5000
        balance(funding) == fundingBefore - 5000
    }

    def "deposit writes two ledger entries"() {
        when:
        def body = json.parseText(client.post("/accounts/$alice/deposits", '{"amount":5000}').body())
        def entries = sql.rows("select account_id, amount from ledger_entries where transfer_id = ? order by amount", [body.id])

        then:
        entries*.account_id == [funding, alice]
        entries*.amount == [-5000, 5000]
    }

    def "deposit to unknown account returns 404 problem"() {
        when:
        def response = client.post("/accounts/999999999/deposits", '{"amount":5000}')

        then:
        response.statusCode() == 404
        json.parseText(response.body()).detail == "account 999999999 not found"
    }

    def "deposit with account id #id returns 400 problem"() {
        when:
        def response = client.post("/accounts/$id/deposits", '{"amount":5000}')

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "account id must be a positive number"

        where:
        id << ["abc", "0"]
    }

    def "deposit to a system account returns 422"() {
        when:
        def response = client.post("/accounts/$funding/deposits", '{"amount":5000}')

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "account $funding is a system account"
    }

    def "deposit is rejected with 400 when #reason"() {
        when:
        def response = client.post("/accounts/$alice/deposits", body)

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == detail
        balance(alice) == 0

        where:
        reason               | body                          || detail
        "body is null"       | 'null'                        || "request body is required"
        "amount is missing"  | '{}'                          || "amount is required"
        "amount is zero"     | '{"amount":0}'                || "amount must be positive"
        "amount is negative" | '{"amount":-1}'               || "amount must be positive"
        "amount is not whole"| '{"amount":10.5}'             || "invalid value for field amount"
        "body sets currency" | '{"amount":1,"currency":"EUR"}' || "unknown field: currency"
    }

    def "30 parallel deposits all complete, so a request never needs a second pool connection"() {
        given:
        def pool = java.util.concurrent.Executors.newFixedThreadPool(30)

        when:
        def statuses = (1..30).collect {
            pool.submit({ client.post("/accounts/$alice/deposits", '{"amount":10}').statusCode() } as java.util.concurrent.Callable)
        }*.get()

        then:
        statuses.every { it == 201 }
        balance(alice) == 300

        cleanup:
        pool.shutdown()
    }

    def "deposit fails with 422 when the balance would overflow"() {
        given:
        sql.executeUpdate("update accounts set balance = ? where id = ?", [Long.MAX_VALUE - 10, alice])
        def fundingBefore = balance(funding)

        when:
        def response = client.post("/accounts/$alice/deposits", '{"amount":100}')

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "amount would overflow an account balance"
        balance(alice) == Long.MAX_VALUE - 10
        balance(funding) == fundingBefore
    }

    private long createAccount(String currency) {
        def response = client.post("/accounts", """{"ownerName":"test","currency":"$currency"}""")
        assert response.statusCode() == 201
        json.parseText(response.body()).id as long
    }

    private long balance(long accountId) {
        json.parseText(client.get("/accounts/$accountId").body()).balance as long
    }
}
