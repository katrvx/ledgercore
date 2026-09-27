package com.ledgercore.transfer

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.testcontainers.containers.GenericContainer
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors

// a slow redis must never hold a database connection: the load test showed pool queues when it did
class RedisOutsideTransactionSpec extends Specification {

    def "a slow redis does not keep a database transaction open"() {
        given: "the app with a redis of its own, connected before it gets slow"
        def redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379)
        redis.start()
        def app = App.start(TestEnv.appConfig("redis://${redis.host}:${redis.getMappedPort(6379)}"))
        def client = new TestClient(app.port())
        def json = new JsonSlurper()
        def alice = json.parseText(client.post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id
        def bob = json.parseText(client.post("/accounts", '{"ownerName":"test","currency":"EUR"}').body()).id
        assert client.post("/accounts/$alice/deposits", '{"amount":1000}').statusCode() == 201
        def body = """{"fromAccountId":$alice,"toAccountId":$bob,"amount":1,"currency":"EUR"}"""
        assert client.post("/transfers", body).statusCode() == 201

        and: "a connection to watch postgres from outside the app"
        def dataSource = Database.connect(TestEnv.appConfig())
        def sql = new Sql(dataSource)
        def pool = Executors.newSingleThreadExecutor()

        when: "redis is paused, so every redis command waits for its 500 ms timeout"
        redis.dockerClient.pauseContainerCmd(redis.containerId).exec()
        def transfer = pool.submit({ client.post("/transfers", body) } as Callable)
        def longestIdleInTransaction = 0
        while (!transfer.isDone()) {
            longestIdleInTransaction = Math.max(longestIdleInTransaction, idleInTransactionMillis(sql))
            Thread.sleep(20)
        }

        then: "the transfer still works, through the database fallback"
        transfer.get().statusCode() == 201

        and: "no transaction sat open while the app waited for redis"
        longestIdleInTransaction < 200

        cleanup:
        redis?.dockerClient?.unpauseContainerCmd(redis.containerId)?.exec()
        pool?.shutdown()
        app?.stop()
        dataSource?.close()
        redis?.stop()
    }

    // how long the longest open transaction has been waiting for its client right now
    private static long idleInTransactionMillis(Sql sql) {
        def row = sql.firstRow("""select coalesce(max(extract(epoch from clock_timestamp() - state_change) * 1000), 0) as ms
                from pg_stat_activity
                where datname = current_database() and state = 'idle in transaction' and pid <> pg_backend_pid()""")
        row.ms as long
    }
}
