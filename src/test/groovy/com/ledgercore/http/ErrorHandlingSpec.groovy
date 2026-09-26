package com.ledgercore.http

import ch.qos.logback.classic.Level
import com.ledgercore.LogCapture
import com.ledgercore.TestClient
import groovy.json.JsonSlurper
import org.slf4j.MDC
import spark.Service
import spock.lang.Shared
import spock.lang.Specification

// a small server with only the http plumbing, so a spec can make a route throw
class ErrorHandlingSpec extends Specification {

    @Shared
    Service http

    @Shared
    TestClient client

    @Shared
    JsonSlurper json = new JsonSlurper()

    def setupSpec() {
        // few threads, so sequential requests land on the same reused jetty threads
        http = Service.ignite().port(0).threadPool(10, 10, 30_000)
        // registered first, so it sees the MDC exactly as the previous request on this thread left it
        http.before("/probe", (req, res) -> req.attribute("mdcAtStart", String.valueOf(MDC.get("requestId"))))
        new RequestFilters().register(http)
        new ErrorHandlers().register(http)
        http.get("/boom", (req, res) -> { throw new IllegalStateException("secret internal detail") })
        http.get("/probe", (req, res) -> req.attribute("mdcAtStart"))
        http.get("/ok", (req, res) -> "ok")
        http.awaitInitialization()
        client = new TestClient(http.port())
    }

    def cleanupSpec() {
        http.stop()
        http.awaitStop()
    }

    def "an unknown route returns a 404 problem, not html"() {
        when:
        def response = client.get("/nope")
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 404
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.title == "Not Found"
        problem.detail == "no route for GET /nope"
        problem.requestId == response.headers().firstValue("X-Request-Id").get()
    }

    def "a wrong method on a known path is a 404 problem too"() {
        when:
        def response = client.send("DELETE", "/ok", null, [:])

        then:
        response.statusCode() == 404
        json.parseText(response.body()).detail == "no route for DELETE /ok"
    }

    def "an unhandled exception returns a 500 problem that hides the cause and is logged with the request id"() {
        given:
        def logs = new LogCapture()

        when:
        def response = client.get("/boom", ["X-Request-Id": "boom-1"])
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 500
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.title == "Internal Server Error"
        problem.detail == "internal error"
        problem.requestId == "boom-1"
        !response.body().contains("secret")
        !response.body().contains("IllegalStateException")

        and:
        def errors = logs.events().findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].MDCPropertyMap.requestId == "boom-1"
        errors[0].throwableProxy.message == "secret internal detail"

        cleanup:
        logs.close()
    }

    def "a request after a 500 does not get the request id of the failed one"() {
        given:
        def logs = new LogCapture()
        client.get("/boom", ["X-Request-Id": "failed-request"])
        LogCapture.waitUntil { logs.events("access").any { it.MDCPropertyMap.requestId == "failed-request" } }
        def eventsBefore = logs.events().size()

        when:
        def response = client.get("/ok")
        LogCapture.waitUntil { logs.events("access").size() >= 2 }
        def laterEvents = logs.events().drop(eventsBefore)

        then:
        response.headers().firstValue("X-Request-Id").get() != "failed-request"
        laterEvents.every { it.MDCPropertyMap.requestId != "failed-request" }
        laterEvents.any { it.loggerName == "access" && it.formattedMessage.startsWith("GET /ok 200 ") }

        cleanup:
        logs.close()
    }

    def "the MDC is empty when a reused thread starts the next request, also after a 500"() {
        when:
        def seen = (1..20).collect {
            client.get("/boom", ["X-Request-Id": "boom-$it".toString()])
            client.get("/probe").body()
        }

        then:
        seen.every { it == "null" }
    }

    def "a failed request is still in the access log"() {
        given:
        def logs = new LogCapture()

        when:
        client.get("/boom", ["X-Request-Id": "boom-access"])
        LogCapture.waitUntil { !logs.events("access").isEmpty() }

        then:
        def access = logs.events("access")
        access.size() == 1
        access[0].formattedMessage ==~ /GET \/boom 500 \d+ms/
        access[0].MDCPropertyMap.requestId == "boom-access"

        cleanup:
        logs.close()
    }
}
