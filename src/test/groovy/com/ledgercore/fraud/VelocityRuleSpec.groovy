package com.ledgercore.fraud

import spock.lang.Specification

class VelocityRuleSpec extends Specification {

    def rule = new VelocityRule(5)

    def "#attempts attempts in the window gives #decision"() {
        given:
        def facts = new FraudFacts(100, attempts, 0, 0, true)

        expect:
        rule.evaluate(facts).decision() == decision

        where:
        attempts || decision
        1        || Decision.APPROVE
        5        || Decision.APPROVE
        6        || Decision.DECLINE
        50       || Decision.DECLINE
    }

    def "decline says how many attempts were seen"() {
        expect:
        new VelocityRule(5).evaluate(new FraudFacts(100, 6, 0, 0, true)).reason() == "velocity: 6 transfers in the window, limit 5"
    }
}
