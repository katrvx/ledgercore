package com.ledgercore.http

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestDatabase
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

class HealthSpec extends Specification {

    @Shared
    App app

    @Shared
    TestClient client

    def setupSpec() {
        app = App.start(TestDatabase.appConfig())
        client = new TestClient(app.port())
    }

    def cleanupSpec() {
        app.stop()
    }

    def "health returns 200 and status UP"() {
        when:
        def response = client.get("/health")

        then:
        response.statusCode() == 200
        response.headers().firstValue("Content-Type").get().startsWith("application/json")
        new JsonSlurper().parseText(response.body()).status == "UP"
    }
}
