package com.ledgercore.config

import ch.qos.logback.classic.Level
import com.ledgercore.App
import com.ledgercore.LogCapture
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import groovy.json.JsonSlurper
import spock.lang.Specification

// without REDIS_URL the service runs on postgres alone, which is a setting, not an outage
class RedisDisabledSpec extends Specification {

    def "without a redis url the app uses the database only and says redis is DISABLED"() {
        given:
        def logs = new LogCapture()
        def app = App.start(TestEnv.appConfig((String) null))
        def client = new TestClient(app.port())
        def json = new JsonSlurper()
        def alice = json.parseText(client.post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id
        def bob = json.parseText(client.post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id
        def body = """{"fromAccountId":$alice,"toAccountId":$bob,"amount":100,"currency":"EUR"}"""

        when:
        def ready = client.get("/ready")
        def deposit = client.post("/accounts/$alice/deposits", '{"amount":1000}')
        def first = client.post("/transfers", body, "no-redis-key")
        def retry = client.post("/transfers", body, "no-redis-key")

        then:
        ready.statusCode() == 200
        json.parseText(ready.body()) == [status: "UP", database: "UP", redis: "DISABLED"]
        deposit.statusCode() == 201
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()

        and: "no outage warning, only one line that redis is not configured"
        logs.events(Redis.name).findAll { it.level == Level.WARN } == []
        logs.events(Redis.name)*.formattedMessage == ["redis is not configured, using the database instead"]

        cleanup:
        app?.stop()
        logs?.close()
    }
}
