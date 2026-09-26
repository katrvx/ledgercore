package com.ledgercore.transfer

import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.sql.Sql
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// shows why TransferService locks accounts in ascending id order
class LockOrderSpec extends Specification {

    static final String DEADLOCK_DETECTED = "40P01"

    @Shared
    HikariDataSource dataSource

    @Shared
    Sql sql

    long lower
    long higher

    def setupSpec() {
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
        dataSource.close()
    }

    def setup() {
        lower = insertAccount()
        higher = insertAccount()
    }

    def "locking the same two rows in opposite order deadlocks"() {
        when:
        def results = runInParallel([[lower, higher], [higher, lower]])

        then:
        results.count { it == DEADLOCK_DETECTED } == 1
        results.count { it == "ok" } == 1
    }

    def "locking the same two rows in id order never deadlocks"() {
        when:
        def results = runInParallel([[lower, higher], [lower, higher]])

        then:
        results == ["ok", "ok"]
    }

    private List<String> runInParallel(List<List<Long>> lockOrders) {
        def barrier = new CyclicBarrier(2)
        def pool = Executors.newFixedThreadPool(2)
        try {
            def futures = lockOrders.collect { order ->
                pool.submit({ lockBoth(order[0], order[1], barrier) } as Callable<String>)
            }
            return futures*.get()
        } finally {
            pool.shutdown()
        }
    }

    private String lockBoth(long firstId, long secondId, CyclicBarrier barrier) {
        Connection connection = dataSource.connection
        connection.autoCommit = false
        try {
            lock(connection, firstId)
            waitForOther(barrier)
            lock(connection, secondId)
            return "ok"
        } catch (SQLException e) {
            return e.SQLState
        } finally {
            connection.rollback()
            connection.close()
        }
    }

    // plain jdbc on purpose: groovy Sql retries a failed query, which would hide the deadlock error
    private static void lock(Connection connection, long id) {
        connection.prepareStatement("select id from accounts where id = ? for update").withCloseable { statement ->
            statement.setLong(1, id)
            statement.executeQuery().close()
        }
    }

    // in opposite order both transactions get here holding one row each
    // in id order the second one is still waiting for the first row, so the wait times out
    private static void waitForOther(CyclicBarrier barrier) {
        try {
            barrier.await(1, TimeUnit.SECONDS)
        } catch (TimeoutException | BrokenBarrierException ignored) {
        }
    }

    private long insertAccount() {
        sql.firstRow("insert into accounts (type, owner_name, currency) values ('CUSTOMER', 'lock test', 'EUR') returning id").id as long
    }
}
