package com.ledgercore.transfer

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestDatabase
import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.json.JsonSlurper
import groovy.sql.Sql
import spock.lang.Shared
import spock.lang.Specification

class TransferApiSpec extends Specification {

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
        app = App.start(TestDatabase.appConfig())
        client = new TestClient(app.port())
        dataSource = Database.connect(TestDatabase.appConfig())
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
        app.stop()
        dataSource.close()
    }

    def setup() {
        alice = createAccount("EUR")
        bob = createAccount("EUR")
        deposit(alice, 1000)
    }

    def "transfer moves money and returns 201 with the transfer"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 300, "EUR"))
        def body = json.parseText(response.body())

        then:
        response.statusCode() == 201
        response.headers().firstValue("Content-Type").get().startsWith("application/json")
        response.headers().firstValue("Location").get() == "/transfers/${body.id}"
        body.id > 0
        body.fromAccountId == alice
        body.toAccountId == bob
        body.amount == 300
        body.currency == "EUR"
        body.status == "COMPLETED"
        body.createdAt.endsWith("Z")

        and:
        balance(alice) == 700
        balance(bob) == 300
    }

    def "transfer writes exactly two ledger entries that sum to zero"() {
        when:
        def body = json.parseText(client.post("/transfers", transfer(alice, bob, 300, "EUR")).body())
        def entries = sql.rows("select account_id, amount, currency from ledger_entries where transfer_id = ? order by amount",
                [body.id])

        then:
        entries.size() == 2
        entries[0].account_id == alice
        entries[0].amount == -300
        entries[1].account_id == bob
        entries[1].amount == 300
        entries*.currency == ["EUR", "EUR"]
    }

    def "created transfer can be read back"() {
        given:
        def created = json.parseText(client.post("/transfers", transfer(alice, bob, 300, "EUR")).body())

        when:
        def response = client.get("/transfers/${created.id}")

        then:
        response.statusCode() == 200
        json.parseText(response.body()) == created
    }

    def "transfer of the full balance leaves zero"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 1000, "EUR"))

        then:
        response.statusCode() == 201
        balance(alice) == 0
        balance(bob) == 1000
    }

    def "transfer fails with 422 when balance is too low and nothing is written"() {
        given:
        def transfersBefore = transferCount(alice)

        when:
        def response = client.post("/transfers", transfer(alice, bob, 1001, "EUR"))
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 422
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.status == 422
        problem.title == "Unprocessable Content"
        problem.detail == "account $alice has insufficient funds"

        and:
        balance(alice) == 1000
        balance(bob) == 0
        transferCount(alice) == transfersBefore
    }

    def "transfer fails with 422 when the accounts have different currencies"() {
        given:
        def carol = createAccount("GBP")

        when:
        def response = client.post("/transfers", transfer(alice, carol, 100, "EUR"))

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail ==
                "cross-currency transfers are not supported: account $alice is EUR and account $carol is GBP"
        balance(alice) == 1000
    }

    def "transfer fails with 422 when the request currency is not the account currency"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 100, "GBP"))

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "account $alice is EUR, not GBP"
        balance(alice) == 1000
    }

    def "transfer fails with 422 when both accounts are the same"() {
        when:
        def response = client.post("/transfers", transfer(alice, alice, 100, "EUR"))

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "fromAccountId and toAccountId must be different"
        balance(alice) == 1000
    }

    def "transfer fails with 422 when the #side account does not exist"() {
        when:
        def response = client.post("/transfers", transfer(id(from), id(to), 100, "EUR"))

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "account 999999999 not found"

        where:
        side   | from      | to
        "from" | "unknown" | "bob"
        "to"   | "alice"   | "unknown"
    }

    def "transfer fails with 422 when the #side account is a system account"() {
        when:
        def response = client.post("/transfers", transfer(id(from), id(to), 100, "EUR"))

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "account ${id("funding")} is a system account"
        balance(alice) == 1000

        where:
        side   | from      | to
        "from" | "funding" | "bob"
        "to"   | "alice"   | "funding"
    }

    def "get unknown transfer returns 404 problem"() {
        when:
        def response = client.get("/transfers/999999999")
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 404
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.detail == "transfer 999999999 not found"
    }

    def "get transfer with id #id returns 400 problem"() {
        when:
        def response = client.get("/transfers/$id")

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "transfer id must be a positive number"

        where:
        id << ["abc", "0", "-1"]
    }

    def "transfer is rejected with 400 when #reason"() {
        when:
        def response = client.post("/transfers", body.replace("ALICE", "$alice").replace("BOB", "$bob"))
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 400
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.detail == detail
        balance(alice) == 1000

        where:
        reason                     | body                                                                                || detail
        "body is null"             | 'null'                                                                              || "request body is required"
        "body is not json"         | 'nope'                                                                              || "request body is not valid json"
        "fromAccountId is missing" | '{"toAccountId":BOB,"amount":100,"currency":"EUR"}'                                 || "fromAccountId is required"
        "fromAccountId is zero"    | '{"fromAccountId":0,"toAccountId":BOB,"amount":100,"currency":"EUR"}'               || "fromAccountId must be a positive number"
        "toAccountId is missing"   | '{"fromAccountId":ALICE,"amount":100,"currency":"EUR"}'                             || "toAccountId is required"
        "toAccountId is negative"  | '{"fromAccountId":ALICE,"toAccountId":-1,"amount":100,"currency":"EUR"}'            || "toAccountId must be a positive number"
        "amount is missing"        | '{"fromAccountId":ALICE,"toAccountId":BOB,"currency":"EUR"}'                        || "amount is required"
        "amount is zero"           | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":0,"currency":"EUR"}'             || "amount must be positive"
        "amount is negative"       | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":-5,"currency":"EUR"}'            || "amount must be positive"
        "amount is not whole"      | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":100.5,"currency":"EUR"}'         || "invalid value for field amount"
        "amount is a string"       | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":"100","currency":"EUR"}'         || "invalid value for field amount"
        "currency is missing"      | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":100}'                            || "currency is required"
        "currency is not iso"      | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":100,"currency":"eur"}'           || "currency must be an ISO 4217 code like EUR"
        "body sets the status"     | '{"fromAccountId":ALICE,"toAccountId":BOB,"amount":100,"currency":"EUR","status":"COMPLETED"}' || "unknown field: status"
    }

    private static String transfer(long from, long to, long amount, String currency) {
        """{"fromAccountId":$from,"toAccountId":$to,"amount":$amount,"currency":"$currency"}"""
    }

    // where tables are built before setup runs, so they name accounts and this resolves them
    private long id(String name) {
        switch (name) {
            case "alice": return alice
            case "bob": return bob
            case "funding": return fundingAccount("EUR")
            default: return 999999999
        }
    }

    private long createAccount(String currency) {
        def response = client.post("/accounts", """{"ownerName":"test","currency":"$currency"}""")
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

    private long fundingAccount(String currency) {
        sql.firstRow("select id from accounts where type = 'SYSTEM' and currency = ?", [currency]).id as long
    }

    private long transferCount(long accountId) {
        sql.firstRow("select count(*) as n from transfers where from_account_id = ?", [accountId]).n as long
    }
}
