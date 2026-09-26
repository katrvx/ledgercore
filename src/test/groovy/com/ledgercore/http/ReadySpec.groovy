package com.ledgercore.http

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.ledgercore.config.Redis
import groovy.json.JsonSlurper
import spark.Service
import spock.lang.Specification

class ReadySpec extends Specification {

    def "ready returns 200 when the database and redis are reachable"() {
        given:
        def app = App.start(TestEnv.appConfig())

        when:
        def response = new TestClient(app.port()).get("/ready")

        then:
        response.statusCode() == 200
        new JsonSlurper().parseText(response.body()) == [status: "UP", database: "UP", redis: "UP"]

        cleanup:
        app.stop()
    }

    def "ready returns 503 when the database is not reachable"() {
        given:
        def dataSource = Database.connect(TestEnv.appConfig())
        def redis = new Redis("redis://localhost:1")
        def http = Service.ignite().port(0)
        new HealthRoutes(dataSource, redis).register(http)
        http.awaitInitialization()
        dataSource.close()

        when:
        def response = new TestClient(http.port()).get("/ready")

        then:
        response.statusCode() == 503
        new JsonSlurper().parseText(response.body()) == [status: "DOWN", database: "DOWN", redis: "DOWN"]

        cleanup:
        http.stop()
        http.awaitStop()
        redis.close()
    }
}
