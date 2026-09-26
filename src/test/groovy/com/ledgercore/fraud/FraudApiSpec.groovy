package com.ledgercore.fraud

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.ledgercore.config.FraudConfig
import com.zaxxer.hikari.HikariDataSource
import groovy.json.JsonSlurper
import groovy.sql.Sql
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration

class FraudApiSpec extends Specification {

    // small limits so a few requests are enough to trigger each rule
    static final FraudConfig CONFIG = new FraudConfig(5, Duration.ofSeconds(60), 50_000, 10, 3, 20, 20_000)

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
    long bob

    def setupSpec() {
        app = App.start(TestEnv.appConfig(CONFIG))
        client = new TestClient(app.port())
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
        app.stop()
        dataSource.close()
    }

    def setup() {
        alice = createAccount()
        bob = createAccount()
        deposit(alice, 1_000_000)
    }

    def "an ordinary transfer is approved and completed"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 1_000))

        then:
        response.statusCode() == 201
        json.parseText(response.body()).status == "COMPLETED"
        balance(bob) == 1_000
    }

    def "a large transfer to a new recipient goes to review and moves no money"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 30_000))
        def body = json.parseText(response.body())

        then:
        response.statusCode() == 202
        response.headers().firstValue("Content-Type").get().startsWith("application/json")
        response.headers().firstValue("Location").get() == "/transfers/${body.id}"
        body.status == "PENDING_REVIEW"
        body.amount == 30_000

        and:
        balance(alice) == 1_000_000
        balance(bob) == 0
        ledgerEntries(body.id as long) == 0
        fraudReason(body.id as long) == "new recipient: 30000 is above 20000"

        and: "the stored transfer can be read back"
        json.parseText(client.get("/transfers/${body.id}").body()).status == "PENDING_REVIEW"
    }

    def "the same large amount to a known recipient is approved"() {
        given:
        client.post("/transfers", transfer(alice, bob, 1_000))

        when:
        def response = client.post("/transfers", transfer(alice, bob, 30_000))

        then:
        response.statusCode() == 201
        balance(bob) == 31_000
    }

    def "an amount far above the usual one goes to review"() {
        given: "three ordinary transfers make the usual amount 1000"
        3.times { assert client.post("/transfers", transfer(alice, bob, 1_000)).statusCode() == 201 }

        when:
        def response = client.post("/transfers", transfer(alice, bob, 10_001))

        then:
        response.statusCode() == 202
        json.parseText(response.body()).status == "PENDING_REVIEW"
        balance(bob) == 3_000
    }

    def "an amount above the absolute limit is declined and stored for audit"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 50_001))
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 422
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.detail == "transfer was declined by risk checks"
        !response.headers().firstValue("Location").isPresent()

        and:
        balance(alice) == 1_000_000
        declinedTransfers(alice) == 1
        ledgerEntriesFrom(alice) == 0
    }

    def "the sixth transfer within a minute is declined"() {
        when:
        def statuses = (1..6).collect { client.post("/transfers", transfer(alice, bob, 100)).statusCode() }

        then:
        statuses == [201, 201, 201, 201, 201, 422]
        balance(bob) == 500
        declinedTransfers(alice) == 1
    }

    def "the response never shows why a transfer was flagged"() {
        when:
        def review = client.post("/transfers", transfer(alice, bob, 30_000))
        def declined = client.post("/transfers", transfer(alice, bob, 50_001))

        then:
        json.parseText(review.body()).keySet() == ["id", "fromAccountId", "toAccountId", "amount", "currency", "status", "createdAt"] as Set
        !review.body().contains("recipient")
        json.parseText(declined.body()).keySet() == ["type", "title", "status", "detail"] as Set
        !declined.body().contains("limit")
    }

    def "a retry of a reviewed transfer is replayed without running the rules again"() {
        given:
        def key = UUID.randomUUID().toString()
        def first = client.post("/transfers", transfer(alice, bob, 30_000), key)

        when: "five more retries would trip the velocity rule if they were evaluated"
        def retries = (1..5).collect { client.post("/transfers", transfer(alice, bob, 30_000), key) }

        then:
        first.statusCode() == 202
        retries*.statusCode().every { it == 202 }
        retries*.body().every { it == first.body() }
        transfersFrom(alice) == 1
    }

    def "deposits are not checked by the fraud rules"() {
        when:
        def statuses = (1..7).collect { client.post("/accounts/$bob/deposits", '{"amount":900000}').statusCode() }

        then:
        statuses.every { it == 201 }
        balance(bob) == 6_300_000
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

    private long balance(long accountId) {
        json.parseText(client.get("/accounts/$accountId").body()).balance as long
    }

    private long ledgerEntries(long transferId) {
        sql.firstRow("select count(*) as n from ledger_entries where transfer_id = ?", [transferId]).n as long
    }

    private long ledgerEntriesFrom(long accountId) {
        sql.firstRow("""select count(*) as n from ledger_entries e join transfers t on t.id = e.transfer_id
                where t.from_account_id = ?""", [accountId]).n as long
    }

    private String fraudReason(long transferId) {
        sql.firstRow("select fraud_reason from transfers where id = ?", [transferId]).fraud_reason
    }

    private long declinedTransfers(long accountId) {
        sql.firstRow("select count(*) as n from transfers where from_account_id = ? and status = 'DECLINED'", [accountId]).n as long
    }

    private long transfersFrom(long accountId) {
        sql.firstRow("select count(*) as n from transfers where from_account_id = ?", [accountId]).n as long
    }
}
