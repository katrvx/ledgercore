package com.ledgercore.fraud

import spock.lang.Specification

class AmountAnomalyRuleSpec extends Specification {

    // absolute limit 1,000,000, anomaly above 10 times the usual amount, needs 5 past transfers
    def rule = new AmountAnomalyRule(1_000_000, 10, 5)

    def "amount #amount with usual #usual and history #history gives #decision"() {
        given:
        def facts = new FraudFacts(amount, 1, history, usual, true)

        expect:
        rule.evaluate(facts).decision() == decision

        where:
        amount    | usual | history || decision
        1_000_000 | 0     | 0       || Decision.APPROVE
        1_000_001 | 0     | 0       || Decision.DECLINE
        1_000_001 | 900_000 | 20    || Decision.DECLINE
        1_000     | 100   | 5       || Decision.APPROVE
        1_001     | 100   | 5       || Decision.REVIEW
        1_001     | 100   | 4       || Decision.APPROVE
        50_000    | 0     | 0       || Decision.APPROVE
    }

    def "a huge usual amount does not overflow"() {
        expect:
        rule.evaluate(new FraudFacts(900_000, 1, 20, Long.MAX_VALUE / 2 as long, true)).decision() == Decision.APPROVE
    }

    def "reasons name the rule"() {
        expect:
        rule.evaluate(new FraudFacts(1_000_001, 1, 0, 0, true)).reason() == "amount: 1000001 is above the absolute limit 1000000"
        rule.evaluate(new FraudFacts(1_001, 1, 5, 100, true)).reason() == "amount: 1001 is more than 10 times the usual 100"
    }
}
