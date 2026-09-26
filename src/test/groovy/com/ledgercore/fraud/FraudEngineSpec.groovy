package com.ledgercore.fraud

import com.ledgercore.config.FraudConfig
import spock.lang.Specification

import java.time.Duration

class FraudEngineSpec extends Specification {

    def "no rule fires gives APPROVE without a reason"() {
        given:
        def engine = new FraudEngine([fixed(Decision.APPROVE, null), fixed(Decision.APPROVE, null)])

        when:
        def result = engine.evaluate(anyFacts())

        then:
        result.decision() == Decision.APPROVE
        result.reason() == null
    }

    def "the strictest decision wins and every reason is kept"() {
        given:
        def engine = new FraudEngine([
                fixed(Decision.REVIEW, "first"),
                fixed(Decision.DECLINE, "second"),
                fixed(Decision.APPROVE, null)])

        when:
        def result = engine.evaluate(anyFacts())

        then:
        result.decision() == Decision.DECLINE
        result.reason() == "first; second"
    }

    def "REVIEW beats APPROVE"() {
        expect:
        new FraudEngine([fixed(Decision.APPROVE, null), fixed(Decision.REVIEW, "r")]).evaluate(anyFacts()).decision() == Decision.REVIEW
    }

    def "engine from config runs all three rules"() {
        given:
        def config = new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 5, 20, 100_000)
        def engine = FraudEngine.fromConfig(config)

        expect:
        engine.evaluate(new FraudFacts(100, 6, 0, 0, true)).decision() == Decision.DECLINE
        engine.evaluate(new FraudFacts(1_001, 1, 5, 100, true)).decision() == Decision.REVIEW
        engine.evaluate(new FraudFacts(100_001, 1, 0, 0, false)).decision() == Decision.REVIEW
        engine.evaluate(new FraudFacts(100, 1, 0, 0, false)).decision() == Decision.APPROVE
    }

    private FraudRule fixed(Decision decision, String reason) {
        { FraudFacts facts -> new RuleResult(decision, reason) } as FraudRule
    }

    private static FraudFacts anyFacts() {
        new FraudFacts(100, 1, 0, 0, true)
    }
}
