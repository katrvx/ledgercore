package com.ledgercore.fraud

import com.ledgercore.MutableClock
import com.ledgercore.TestEnv
import com.ledgercore.config.Database
import com.ledgercore.config.FraudConfig
import com.ledgercore.config.Redis
import com.zaxxer.hikari.HikariDataSource
import groovy.sql.Sql
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration

class FraudFactsCollectorSpec extends Specification {

    static final FraudConfig CONFIG = new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 3, 4, 100_000)

    @Shared
    HikariDataSource dataSource

    @Shared
    Sql sql

    @Shared
    RedisClient redisClient

    @Shared
    RedisCommands<String, String> redisCommands

    MutableClock clock = new MutableClock()
    Redis redis
    long alice
    long bob

    def setupSpec() {
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
        redisClient = RedisClient.create(TestEnv.redisUrl())
        redisCommands = redisClient.connect().sync()
    }

    def cleanupSpec() {
        dataSource.close()
        redisClient.shutdown()
    }

    def setup() {
        redis = new Redis(TestEnv.redisUrl(), java.time.Duration.ofMillis(500))
        alice = insertAccount()
        bob = insertAccount()
    }

    def cleanup() {
        redis.close()
    }

    def "attempts are counted in a sliding window that includes this attempt"() {
        given:
        def collector = collector(redis)

        when:
        def counts = (1..3).collect { collect(collector, alice, bob, 100).attemptsInWindow() }

        then:
        counts == [1, 2, 3]
    }

    def "attempts older than the window stop counting and are trimmed"() {
        given:
        def collector = collector(redis)
        3.times { collect(collector, alice, bob, 100) }

        when:
        clock.advance(Duration.ofSeconds(61))
        def facts = collect(collector, alice, bob, 100)

        then:
        facts.attemptsInWindow() == 1
        redisCommands.zcard(velocityKey(alice)) == 1
        redisCommands.ttl(velocityKey(alice)) > 0
        redisCommands.ttl(velocityKey(alice)) <= 60
    }

    def "windows of different accounts are separate"() {
        given:
        def collector = collector(redis)
        3.times { collect(collector, alice, bob, 100) }

        expect:
        collect(collector, bob, alice, 100).attemptsInWindow() == 1
    }

    def "without redis the window is counted from stored transfers"() {
        given:
        def collector = collector(new Redis("redis://localhost:1", java.time.Duration.ofMillis(500)))
        2.times { insertTransfer(alice, bob, 100, "COMPLETED") }
        insertTransfer(alice, bob, 100, "DECLINED")

        expect: "three stored attempts plus this one"
        collect(collector, alice, bob, 100).attemptsInWindow() == 4
    }

    def "usual amount is the average of the last completed transfers only"() {
        given: "history size 4, so the oldest completed one is left out"
        insertTransfer(alice, bob, 9_999, "COMPLETED")
        [100, 200, 300, 400].each { insertTransfer(alice, bob, it, "COMPLETED") }
        insertTransfer(alice, bob, 50_000, "PENDING_REVIEW")
        insertTransfer(alice, bob, 60_000, "DECLINED")

        when:
        def facts = collect(collector(redis), alice, bob, 100)

        then:
        facts.historySize() == 4
        facts.usualAmount() == 250
    }

    def "an account without history has usual amount 0"() {
        when:
        def facts = collect(collector(redis), alice, bob, 100)

        then:
        facts.historySize() == 0
        facts.usualAmount() == 0
    }

    def "a recipient is known only after a completed transfer to it"() {
        given:
        def collector = collector(redis)

        when:
        def before = collect(collector, alice, bob, 100).knownRecipient()
        insertTransfer(alice, bob, 100, "PENDING_REVIEW")
        def afterPending = collect(collector, alice, bob, 100).knownRecipient()
        insertTransfer(alice, bob, 100, "COMPLETED")
        def afterCompleted = collect(collector, alice, bob, 100).knownRecipient()
        def otherDirection = collect(collector, bob, alice, 100).knownRecipient()

        then:
        !before
        !afterPending
        afterCompleted
        !otherDirection
    }

    private FraudFactsCollector collector(Redis redis) {
        new FraudFactsCollector(DSL.using(dataSource, SQLDialect.POSTGRES), redis, CONFIG, clock)
    }

    private FraudFacts collect(FraudFactsCollector collector, long from, long to, long amount) {
        // same order as a transfer: count the attempt first, then read the facts
        collector.collect(DSL.using(dataSource, SQLDialect.POSTGRES), from, to, amount, collector.recordAttempt(from))
    }

    private static String velocityKey(long accountId) {
        "fraud:velocity:" + accountId
    }

    private long insertAccount() {
        sql.firstRow("insert into accounts (type, owner_name, currency) values ('CUSTOMER', 'facts', 'EUR') returning id").id as long
    }

    private void insertTransfer(long from, long to, long amount, String status) {
        sql.execute("""insert into transfers (from_account_id, to_account_id, amount, currency, status)
                values (?, ?, ?, 'EUR', ?)""", [from, to, amount, status])
    }
}
