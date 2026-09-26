package com.ledgercore.idempotency

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors

// correctness must not depend on redis, so these specs run without it
class RedisDownSpec extends Specification {

    static final int DUPLICATES = 10

    @Shared
    HikariDataSource dataSource

    @Shared
    Sql sql

    @Shared
    JsonSlurper json = new JsonSlurper()

    def setupSpec() {
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
        dataSource.close()
    }

    def "app starts without redis and handles duplicates with the database"() {
        given: "nothing listens on port 1"
        def app = App.start(TestEnv.appConfig("redis://localhost:1"))
        def client = new TestClient(app.port())
        def alice = createAccount(client)
        def bob = createAccount(client)
        deposit(client, alice, 1000)
        def key = UUID.randomUUID().toString()

        when:
        def ready = client.get("/ready")
        def first = client.post("/transfers", transfer(alice, bob, 100), key)
        def retry = client.post("/transfers", transfer(alice, bob, 100), key)
        def other = client.post("/transfers", transfer(alice, bob, 200), key)

        then:
        ready.statusCode() == 200
        json.parseText(ready.body()) == [status: "UP", database: "UP", redis: "DOWN"]
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()
        other.statusCode() == 422
        balance(client, alice) == 900
        transferCount(alice) == 1

        cleanup:
        app.stop()
    }

    def "when redis dies at runtime the database still stops duplicates"() {
        given: "a redis of its own that this spec can stop"
        def redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379)
        redis.start()
        def app = App.start(TestEnv.appConfig("redis://${redis.host}:${redis.getMappedPort(6379)}"))
        def client = new TestClient(app.port())
        def alice = createAccount(client)
        def bob = createAccount(client)
        deposit(client, alice, 1000)
        def key = UUID.randomUUID().toString()
        def first = client.post("/transfers", transfer(alice, bob, 100), key)
        def readyBefore = client.get("/ready")

        when: "redis goes away"
        redis.stop()
        def readyAfter = client.get("/ready")
        def retry = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        first.statusCode() == 201
        json.parseText(readyBefore.body()).redis == "UP"
        readyAfter.statusCode() == 200
        json.parseText(readyAfter.body()).redis == "DOWN"
        retry.statusCode() == 201
        retry.body() == first.body()
        balance(client, alice) == 900

        when: "10 identical requests race with no redis lock"
        def pool = Executors.newFixedThreadPool(DUPLICATES)
        def raceKey = UUID.randomUUID().toString()
        def responses = (1..DUPLICATES).collect {
            pool.submit({ client.post("/transfers", transfer(alice, bob, 100), raceKey) } as Callable)
        }*.get()

        then:
        responses*.statusCode().every { it == 201 }
        (responses*.body() as Set).size() == 1
        balance(client, alice) == 800
        transferCount(alice) == 2

        cleanup:
        pool?.shutdown()
        app.stop()
        redis.stop()
    }

    private static String transfer(long from, long to, long amount) {
        """{"fromAccountId":$from,"toAccountId":$to,"amount":$amount,"currency":"EUR"}"""
    }

    private long createAccount(TestClient client) {
        def response = client.post("/accounts", '{"ownerName":"test","currency":"EUR"}')
        assert response.statusCode() == 201
        json.parseText(response.body()).id as long
    }

    private void deposit(TestClient client, long accountId, long amount) {
        def response = client.post("/accounts/$accountId/deposits", """{"amount":$amount}""")
        assert response.statusCode() == 201
    }

    private long balance(TestClient client, long accountId) {
        json.parseText(client.get("/accounts/$accountId").body()).balance as long
    }

    private long transferCount(long accountId) {
        sql.firstRow("select count(*) as n from transfers where from_account_id = ?", [accountId]).n as long
    }
}
