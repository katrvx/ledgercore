package com.ledgercore.http

import ch.qos.logback.classic.Level
import com.ledgercore.App
import com.ledgercore.LogCapture
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.FraudConfig
import groovy.json.JsonSlurper
import org.testcontainers.postgresql.PostgreSQLContainer
import spock.lang.Specification

import java.time.Duration

// a database of its own, so this spec can stop it without breaking the others
class DatabaseDownSpec extends Specification {

    def "when the database is down requests get 503 with Retry-After, not 500"() {
        given:
        def postgres = new PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
        def app = App.start(TestEnv.appConfig(postgres.jdbcUrl, TestEnv.redisUrl(), Duration.ofMillis(500), FraudConfig.defaults()))
        def client = new TestClient(app.port())
        def json = new JsonSlurper()
        def alice = json.parseText(client.post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id
        def bob = json.parseText(client.post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id
        def logs = new LogCapture()

        when:
        postgres.stop()
        def started = System.currentTimeMillis()
        def transfer = client.post("/transfers", """{"fromAccountId":$alice,"toAccountId":$bob,"amount":1,"currency":"EUR"}""")
        def waited = System.currentTimeMillis() - started
        def read = client.get("/accounts/$alice")
        def problem = json.parseText(transfer.body())

        then:
        transfer.statusCode() == 503
        transfer.headers().firstValue("Retry-After").get() == "1"
        transfer.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.title == "Service Unavailable"
        problem.detail == "the database is not available, try again"
        problem.requestId == transfer.headers().firstValue("X-Request-Id").get()
        read.statusCode() == 503

        and: "the pool gives up after 5 seconds"
        waited < 8_000

        and: "one short warning per request in the log, no stack trace and no error"
        logs.events().findAll { it.level == Level.ERROR } == []
        def warnings = logs.events(ErrorHandlers.name).findAll { it.level == Level.WARN }
        warnings.size() == 2
        warnings.every { it.throwableProxy == null }
        warnings[0].formattedMessage.startsWith("database is not available on POST /transfers")

        and: "the process itself is still alive"
        client.get("/health").statusCode() == 200

        cleanup:
        logs?.close()
        app?.stop()
        postgres?.stop()
    }
}
