package com.ledgercore.ledger

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class TransactionsApiSpec extends Specification {

    @Shared
    App app

    @Shared
    TestClient client

    @Shared
    JsonSlurper json = new JsonSlurper()

    long alice
    long bob

    def setupSpec() {
        app = App.start(TestEnv.appConfig(TestEnv.NO_FRAUD_LIMITS))
        client = new TestClient(app.port())
    }

    def cleanupSpec() {
        app.stop()
    }

    def setup() {
        alice = createAccount()
        bob = createAccount()
    }

    def "an account without transfers has an empty history"() {
        when:
        def page = json.parseText(client.get("/accounts/$alice/transactions").body())

        then:
        page.items == []
        page.nextCursor == null
    }

    def "entries show the signed amount and the other side of the transfer"() {
        given:
        def deposit = json.parseText(post("/accounts/$alice/deposits", '{"amount":1000}').body())
        def transfer = json.parseText(post("/transfers", transfer(alice, bob, 300)).body())

        when:
        def response = client.get("/accounts/$alice/transactions")
        def items = json.parseText(response.body()).items

        then:
        response.statusCode() == 200
        response.headers().firstValue("Content-Type").get().startsWith("application/json")
        items.size() == 2

        and: "newest first"
        items[0].transferId == transfer.id
        items[0].amount == -300
        items[0].counterpartyAccountId == bob
        items[0].currency == "EUR"
        items[0].createdAt.endsWith("Z")
        items[1].transferId == deposit.id
        items[1].amount == 1000
        items[1].counterpartyAccountId == deposit.fromAccountId
        items[0].entryId > items[1].entryId
    }

    def "pages of 10 walk through all 26 entries once, newest first"() {
        given:
        post("/accounts/$alice/deposits", '{"amount":100000}')
        25.times { post("/transfers", transfer(alice, bob, 10)) }

        when:
        def pages = walk("/accounts/$alice/transactions?limit=10")
        def ids = pages.collectMany { it.items*.entryId }

        then:
        pages*.items*.size() == [10, 10, 6]
        pages*.nextCursor.collect { it != null } == [true, true, false]
        ids.size() == 26
        ids.toSet().size() == 26
        ids == ids.sort(false).reverse()
    }

    def "the default page size is 20"() {
        given:
        post("/accounts/$alice/deposits", '{"amount":100000}')
        24.times { post("/transfers", transfer(alice, bob, 10)) }

        expect:
        json.parseText(client.get("/accounts/$alice/transactions").body()).items.size() == 20
    }

    def "a new transfer between two pages does not repeat or shift entries"() {
        given:
        post("/accounts/$alice/deposits", '{"amount":100000}')
        9.times { post("/transfers", transfer(alice, bob, 10)) }
        def first = json.parseText(client.get("/accounts/$alice/transactions?limit=5").body())

        when: "a newer entry appears before the client asks for page two"
        post("/transfers", transfer(alice, bob, 10))
        def second = json.parseText(client.get("/accounts/$alice/transactions?limit=5&cursor=${first.nextCursor}").body())

        then:
        (first.items*.entryId + second.items*.entryId).toSet().size() == 10
        second.items*.entryId.every { it < first.items.last().entryId }
    }

    def "limit #limit returns 400"() {
        when:
        def response = client.get("/accounts/$alice/transactions?limit=$limit")

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "limit must be a number from 1 to 100"

        where:
        limit << ["0", "101", "abc", "-1", "1.5"]
    }

    def "cursor #reason returns 400"() {
        when:
        def response = client.get("/accounts/$alice/transactions?cursor=$cursor")

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "cursor is not valid"

        where:
        reason               | cursor
        "that is not base64" | "!!!"
        "that is not a number" | encode("abc")
        "that is negative"   | encode("-5")
        "that is zero"       | encode("0")
    }

    def "history of an unknown account returns 404"() {
        when:
        def response = client.get("/accounts/999999999/transactions")

        then:
        response.statusCode() == 404
        json.parseText(response.body()).detail == "account 999999999 not found"
    }

    def "history with a non numeric account id returns 400"() {
        expect:
        client.get("/accounts/abc/transactions").statusCode() == 400
    }

    private List walk(String firstPath) {
        def pages = []
        def path = firstPath
        while (path != null) {
            def page = json.parseText(client.get(path).body())
            pages << page
            path = page.nextCursor == null ? null : firstPath + "&cursor=" + page.nextCursor
        }
        pages
    }

    private static String encode(String text) {
        Base64.urlEncoder.withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8))
    }

    private static String transfer(long from, long to, long amount) {
        """{"fromAccountId":$from,"toAccountId":$to,"amount":$amount,"currency":"EUR"}"""
    }

    private def post(String path, String body) {
        def response = client.post(path, body)
        assert response.statusCode() == 201
        response
    }

    private long createAccount() {
        json.parseText(post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id as long
    }
}
