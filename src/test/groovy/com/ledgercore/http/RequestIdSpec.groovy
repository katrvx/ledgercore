package com.ledgercore.http

import com.ledgercore.App
import com.ledgercore.LogCapture
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

class RequestIdSpec extends Specification {

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

    def "a request without X-Request-Id gets a new uuid"() {
        when:
        def id = client.get("/health").headers().firstValue("X-Request-Id").get()

        then:
        UUID.fromString(id).toString() == id
    }

    def "a valid X-Request-Id from the client is kept"() {
        expect:
        client.get("/health", ["X-Request-Id": "abc.DEF_123-x"]).headers().firstValue("X-Request-Id").get() == "abc.DEF_123-x"
    }

    def "an X-Request-Id that is #reason is replaced"() {
        when:
        def id = client.get("/health", ["X-Request-Id": value]).headers().firstValue("X-Request-Id").get()

        then:
        id != value
        UUID.fromString(id).toString() == id

        where:
        reason               | value
        "too long"           | "a" * 65
        "full of odd chars"  | "id with spaces"
        "json looking"       | '{"x":1}'
    }

    def "every problem body carries the request id"() {
        when:
        def response = client.get("/accounts/999999999", ["X-Request-Id": "trace-me"])

        then:
        response.statusCode() == 404
        json.parseText(response.body()).requestId == "trace-me"
    }

    def "the access log has method, path, status and time, and never the body"() {
        given:
        def logs = new LogCapture()

        when:
        def response = client.post("/accounts", '{"ownerName":"Very Secret Owner","currency":"EUR"}')
        LogCapture.waitUntil { !logs.events("access").isEmpty() }

        then:
        response.statusCode() == 201
        def access = logs.events("access")
        access.size() == 1
        access[0].formattedMessage ==~ /POST \/accounts 201 \d+ms/
        access[0].MDCPropertyMap.requestId == response.headers().firstValue("X-Request-Id").get()
        logs.events().every { !it.formattedMessage.contains("Very Secret Owner") }

        cleanup:
        logs.close()
    }
}
