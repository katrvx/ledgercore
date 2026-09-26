package com.ledgercore.http

import com.ledgercore.App
import groovy.json.JsonSlurper
import spark.Service
import spock.lang.Shared
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HealthSpec extends Specification {

    @Shared
    Service app

    @Shared
    HttpClient client = HttpClient.newHttpClient()

    def setupSpec() {
        app = App.start(0)
    }

    def cleanupSpec() {
        app.stop()
        app.awaitStop()
    }

    def "health returns 200 and status UP"() {
        given:
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}/health")).build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.headers().firstValue("Content-Type").get().startsWith("application/json")
        new JsonSlurper().parseText(response.body()).status == "UP"
    }
}
