package com.ledgercore.idempotency

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.json.JsonSlurper
import groovy.sql.Sql
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors

class IdempotencySpec extends Specification {

    @Shared
    App app

    @Shared
    TestClient client

    @Shared
    HikariDataSource dataSource

    @Shared
    Sql sql

    @Shared
    RedisClient redisClient

    @Shared
    RedisCommands<String, String> redis

    @Shared
    JsonSlurper json = new JsonSlurper()

    long alice
    long bob
    String key

    def setupSpec() {
        app = App.start(TestEnv.appConfig())
        client = new TestClient(app.port())
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
        redisClient = RedisClient.create(TestEnv.redisUrl())
        redis = redisClient.connect().sync()
    }

    def cleanupSpec() {
        app.stop()
        dataSource.close()
        redisClient.shutdown()
    }

    def setup() {
        alice = createAccount()
        bob = createAccount()
        deposit(alice, 1000)
        key = UUID.randomUUID().toString()
    }

    def "transfer without Idempotency-Key returns 400"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 100), null)

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "Idempotency-Key header is required"
        balance(alice) == 1000
    }

    def "deposit without Idempotency-Key returns 400"() {
        when:
        def response = client.post("/accounts/$alice/deposits", '{"amount":100}', null)

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "Idempotency-Key header is required"
        balance(alice) == 1000
    }

    def "Idempotency-Key that is #reason returns 400"() {
        when:
        def response = client.post("/transfers", transfer(alice, bob, 100), value)

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == detail

        where:
        reason     | value     || detail
        "blank"    | "   "     || "Idempotency-Key header is required"
        "too long" | "k" * 256 || "Idempotency-Key must be at most 255 characters"
    }

    def "retry with the same key returns the same response and moves money once"() {
        when:
        def first = client.post("/transfers", transfer(alice, bob, 100), key)
        def retry = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()
        retry.headers().firstValue("Location").get() == first.headers().firstValue("Location").get()
        retry.headers().firstValue("Content-Type").get().startsWith("application/json")

        and:
        balance(alice) == 900
        transferCount(alice) == 1
    }

    def "retry with reordered fields and different whitespace is the same request"() {
        when:
        def first = client.post("/transfers", transfer(alice, bob, 100), key)
        def retry = client.post("/transfers", """{ "currency" : "EUR", "amount":100,
                "toAccountId": $bob, "fromAccountId": $alice }""", key)

        then:
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()
        balance(alice) == 900
    }

    def "same key with a different body returns 422 and does nothing"() {
        given:
        client.post("/transfers", transfer(alice, bob, 100), key)

        when:
        def response = client.post("/transfers", transfer(alice, bob, 200), key)

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "Idempotency-Key was already used with a different request"
        balance(alice) == 900
        transferCount(alice) == 1
    }

    def "same key on a different endpoint returns 422"() {
        given:
        client.post("/transfers", transfer(alice, bob, 100), key)

        when:
        def response = client.post("/accounts/$alice/deposits", '{"amount":100}', key)

        then:
        response.statusCode() == 422
        json.parseText(response.body()).detail == "Idempotency-Key was already used with a different request"
        balance(alice) == 900
    }

    def "duplicate while the first request is still running returns 409"() {
        given: "alice's row is locked, so the first request waits inside its transaction"
        def connection = dataSource.connection
        connection.autoCommit = false
        new Sql(connection).execute("select id from accounts where id = ? for update", [alice])
        def pool = Executors.newSingleThreadExecutor()
        def first = pool.submit({ client.post("/transfers", transfer(alice, bob, 100), key) } as Callable)
        waitUntil { redis.exists("idempotency:lock:" + key) == 1 }

        when:
        def duplicate = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        duplicate.statusCode() == 409
        duplicate.headers().firstValue("Retry-After").get() == "1"
        duplicate.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        json.parseText(duplicate.body()).detail == "a request with this Idempotency-Key is still in progress"

        when: "the row is released"
        connection.rollback()
        connection.close()
        def firstResponse = first.get()
        def retry = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        firstResponse.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == firstResponse.body()
        balance(alice) == 900

        cleanup:
        pool.shutdown()
    }

    def "a rejected transfer is replayed as the same 422 even after money arrived"() {
        given:
        def first = client.post("/transfers", transfer(alice, bob, 5000), key)
        deposit(alice, 10000)

        when:
        def retry = client.post("/transfers", transfer(alice, bob, 5000), key)

        then:
        first.statusCode() == 422
        json.parseText(first.body()).detail == "account $alice has insufficient funds"
        retry.statusCode() == 422
        retry.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        retry.body() == first.body()
        balance(alice) == 11000
        transferCount(alice) == 0
    }

    def "a 422 that loses the race to a duplicate replays the duplicate's response"() {
        given: "a duplicate with the same key is committing its 201 while this request fails with 422"
        def body = transfer(alice, bob, 5000)
        def duplicate = dataSource.connection
        duplicate.autoCommit = false
        new Sql(duplicate).execute("""insert into idempotency_keys (key, request_hash, status, location, response_body)
                values (?, ?, 201, '/transfers/1', '{"replayed":true}')""", [key, requestHash("POST", "/transfers", body)])
        def pool = Executors.newSingleThreadExecutor()
        def request = pool.submit({ client.post("/transfers", body, key) } as Callable)
        waitUntil { insertIsWaitingForLock() }

        when: "the duplicate commits first"
        duplicate.commit()
        duplicate.close()
        def response = request.get()

        then:
        response.statusCode() == 201
        response.headers().firstValue("Location").get() == "/transfers/1"
        response.body() == '{"replayed":true}'
        balance(alice) == 1000

        cleanup:
        pool.shutdown()
    }

    def "a redis value that #reason is treated like no value"() {
        given:
        redis.set("idempotency:response:" + key, value)

        when:
        def first = client.post("/transfers", transfer(alice, bob, 100), key)
        def retry = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()
        balance(alice) == 900

        and: "the broken value was replaced by a real record"
        json.parseText(redis.get("idempotency:response:" + key)).response.status == 201

        where:
        reason                        | value
        "is not json"                 | 'not json at all'
        "has no response"             | '{"requestHash":"abc"}'
        "has a field from the future" | '{"requestHash":"abc","response":{"status":201,"location":"/transfers/1","body":"{}"},"version":2}'
    }

    def "a saved 422 survives when redis is flushed"() {
        given:
        def first = client.post("/transfers", transfer(alice, bob, 5000), key)
        redis.flushall()
        deposit(alice, 10000)

        when:
        def retry = client.post("/transfers", transfer(alice, bob, 5000), key)

        then:
        first.statusCode() == 422
        retry.statusCode() == 422
        retry.body() == first.body()
        transferCount(alice) == 0
    }

    def "a saved 201 survives when redis is flushed"() {
        given:
        def first = client.post("/transfers", transfer(alice, bob, 100), key)
        redis.flushall()

        when:
        def retry = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()
        retry.headers().firstValue("Location").get() == first.headers().firstValue("Location").get()
        balance(alice) == 900
        transferCount(alice) == 1
    }

    def "a 400 is not saved, so the key can be reused with a fixed body"() {
        given:
        def bad = client.post("/transfers", """{"fromAccountId":$alice,"toAccountId":$bob,"amount":-1,"currency":"EUR"}""", key)

        when:
        def fixed = client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        bad.statusCode() == 400
        fixed.statusCode() == 201
        balance(alice) == 900
    }

    def "deposit retry with the same key deposits once"() {
        when:
        def first = client.post("/accounts/$alice/deposits", '{"amount":500}', key)
        def retry = client.post("/accounts/$alice/deposits", '{"amount":500}', key)

        then:
        first.statusCode() == 201
        retry.statusCode() == 201
        retry.body() == first.body()
        balance(alice) == 1500
    }

    def "deposit to an unknown account is replayed as the same 404"() {
        when:
        def first = client.post("/accounts/999999999/deposits", '{"amount":500}', key)
        def retry = client.post("/accounts/999999999/deposits", '{"amount":500}', key)

        then:
        first.statusCode() == 404
        retry.statusCode() == 404
        retry.body() == first.body()
    }

    def "different keys with the same body make two transfers"() {
        when:
        def first = client.post("/transfers", transfer(alice, bob, 100))
        def second = client.post("/transfers", transfer(alice, bob, 100))

        then:
        first.statusCode() == 201
        second.statusCode() == 201
        json.parseText(first.body()).id != json.parseText(second.body()).id
        balance(alice) == 800
    }

    def "the response is saved in redis and the lock is released"() {
        when:
        client.post("/transfers", transfer(alice, bob, 100), key)

        then:
        redis.exists("idempotency:response:" + key) == 1
        redis.exists("idempotency:lock:" + key) == 0
        redis.ttl("idempotency:response:" + key) > 0
    }

    private static String transfer(long from, long to, long amount) {
        """{"fromAccountId":$from,"toAccountId":$to,"amount":$amount,"currency":"EUR"}"""
    }

    private long createAccount() {
        def response = client.post("/accounts", '{"ownerName":"test","currency":"EUR"}')
        assert response.statusCode() == 201
        json.parseText(response.body()).id as long
    }

    private void deposit(long accountId, long amount) {
        def response = client.post("/accounts/$accountId/deposits", """{"amount":$amount}""")
        assert response.statusCode() == 201
    }

    private long balance(long accountId) {
        json.parseText(client.get("/accounts/$accountId").body()).balance as long
    }

    private long transferCount(long accountId) {
        sql.firstRow("select count(*) as n from transfers where from_account_id = ?", [accountId]).n as long
    }

    // the same hash the service builds: method, path and the json with sorted keys
    private static String requestHash(String method, String path, String body) {
        def sorted = new TreeMap(new JsonSlurper().parseText(body) as Map)
        def canonical = groovy.json.JsonOutput.toJson(sorted)
        def digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.digest((method + " " + path + "\n" + canonical).getBytes("UTF-8")).encodeHex().toString()
    }

    private boolean insertIsWaitingForLock() {
        def row = sql.firstRow("""select count(*) as n from pg_stat_activity
                where wait_event_type = 'Lock' and query like '%idempotency_keys%'""")
        row.n > 0
    }

    private static void waitUntil(Closure<Boolean> condition) {
        for (int i = 0; i < 50; i++) {
            if (condition()) {
                return
            }
            Thread.sleep(100)
        }
        throw new AssertionError("condition was not met in 5 seconds")
    }
}
