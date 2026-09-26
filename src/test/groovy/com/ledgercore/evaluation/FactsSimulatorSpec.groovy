package com.ledgercore.evaluation

import com.ledgercore.config.FraudConfig
import com.ledgercore.fraud.Decision
import spock.lang.Specification

import java.time.Duration
import java.time.Instant

// the simulator must count facts the same way FraudFactsCollector does
class FactsSimulatorSpec extends Specification {

    static final FraudConfig CONFIG = new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 3, 4, 100_000)
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z")

    def simulator = new FactsSimulator(CONFIG)

    def "the window counts every attempt including this one, even declined ones"() {
        when:
        simulator.record(tx(0, 1, 2, 100), Decision.DECLINE)
        simulator.record(tx(10, 1, 2, 100), Decision.APPROVE)
        def facts = simulator.factsFor(tx(20, 1, 2, 100))

        then:
        facts.attemptsInWindow() == 3
    }

    def "attempts older than the window stop counting"() {
        when:
        simulator.record(tx(0, 1, 2, 100), Decision.APPROVE)
        def facts = simulator.factsFor(tx(61, 1, 2, 100))

        then:
        facts.attemptsInWindow() == 1
    }

    def "history and known recipients only include approved transfers"() {
        when:
        simulator.record(tx(0, 1, 2, 9_999), Decision.APPROVE)
        [100, 200, 300, 400].eachWithIndex { amount, i -> simulator.record(tx(100 * (i + 1), 1, 2, amount), Decision.APPROVE) }
        simulator.record(tx(600, 1, 3, 50_000), Decision.REVIEW)
        def toBob = simulator.factsFor(tx(700, 1, 2, 100))
        def toCarol = simulator.factsFor(tx(700, 1, 3, 100))

        then:
        toBob.historySize() == 4
        toBob.usualAmount() == 250
        toBob.knownRecipient()
        !toCarol.knownRecipient()
    }

    private static LabeledTransaction tx(long seconds, long from, long to, long amount) {
        new LabeledTransaction(T0.plusSeconds(seconds), from, to, amount, false, "regular")
    }
}
