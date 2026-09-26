package com.ledgercore.account

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

class AccountApiSpec extends Specification {

    @Shared
    App app

    @Shared
    TestClient client

    @Shared
    JsonSlurper json = new JsonSlurper()

    def setupSpec() {
        app = App.start(TestEnv.appConfig())
        client = new TestClient(app.port())
    }

    def cleanupSpec() {
        app.stop()
    }

    def "create account returns 201 with the new account"() {
        when:
        def response = client.post("/accounts", '{"ownerName":"Alice","currency":"EUR"}')
        def body = json.parseText(response.body())

        then:
        response.statusCode() == 201
        response.headers().firstValue("Content-Type").get().startsWith("application/json")
        response.headers().firstValue("Location").get() == "/accounts/${body.id}"
        body.id > 0
        body.type == "CUSTOMER"
        body.ownerName == "Alice"
        body.currency == "EUR"
        body.balance == 0
        body.createdAt.endsWith("Z")
    }

    def "owner name is trimmed"() {
        when:
        def response = client.post("/accounts", '{"ownerName":"  Bob  ","currency":"GBP"}')

        then:
        response.statusCode() == 201
        json.parseText(response.body()).ownerName == "Bob"
    }

    def "owner name keeps non ascii letters"() {
        when:
        def response = client.post("/accounts", '{"ownerName":"Zoë Müller","currency":"EUR"}')

        then:
        response.statusCode() == 201
        json.parseText(response.body()).ownerName == "Zoë Müller"
    }

    def "created account can be read back"() {
        given:
        def created = json.parseText(client.post("/accounts", '{"ownerName":"Alice","currency":"USD"}').body())

        when:
        def response = client.get("/accounts/${created.id}")

        then:
        response.statusCode() == 200
        json.parseText(response.body()) == created
    }

    def "get unknown account returns 404 problem"() {
        when:
        def response = client.get("/accounts/999999999")
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 404
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.status == 404
        problem.title == "Not Found"
        problem.detail == "account 999999999 not found"
    }

    def "get account with id #id returns 400 problem"() {
        when:
        def response = client.get("/accounts/$id")

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "account id must be a positive number"

        where:
        id << ["abc", "0", "-1", "99999999999999999999"]
    }

    def "create account is rejected when #reason"() {
        when:
        def response = client.post("/accounts", body)
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 400
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.status == 400
        problem.title == "Bad Request"
        problem.detail == detail

        where:
        reason                          | body                                                         || detail
        "owner name is missing"         | '{"currency":"EUR"}'                                         || "ownerName is required"
        "owner name is blank"           | '{"ownerName":"   ","currency":"EUR"}'                       || "ownerName is required"
        "owner name is too long"        | '{"ownerName":"' + 'a' * 201 + '","currency":"EUR"}'         || "ownerName must be at most 200 characters"
        "owner name has a null byte"    | '{"ownerName":"Al\\u0000ice","currency":"EUR"}'              || "ownerName must not contain control characters"
        "owner name has a newline"      | '{"ownerName":"Al\\nice","currency":"EUR"}'                  || "ownerName must not contain control characters"
        "currency is missing"           | '{"ownerName":"Alice"}'                                      || "currency is required"
        "currency is lowercase"         | '{"ownerName":"Alice","currency":"eur"}'                     || "currency must be an ISO 4217 code like EUR"
        "currency is not an ISO code"   | '{"ownerName":"Alice","currency":"ABC"}'                     || "currency must be an ISO 4217 code like EUR"
        "currency has no funding"       | '{"ownerName":"Alice","currency":"JPY"}'                     || "currency JPY is not supported"
        "body tries to set the balance" | '{"ownerName":"Alice","currency":"EUR","balance":100}'       || "unknown field: balance"
        "body tries to set the type"    | '{"ownerName":"Alice","currency":"EUR","type":"SYSTEM"}'     || "unknown field: type"
        "body is not json"              | 'not json'                                                   || "request body is not valid json"
        "body is empty"                 | ''                                                           || "request body is not valid json"
        "body is null"                  | 'null'                                                       || "request body is required"
    }
}
