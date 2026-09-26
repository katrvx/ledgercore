package com.ledgercore.fraud

import spock.lang.Specification

class NewRecipientRuleSpec extends Specification {

    def rule = new NewRecipientRule(100_000)

    def "amount #amount to a #recipient recipient gives #decision"() {
        given:
        def facts = new FraudFacts(amount, 1, 0, 0, recipient == "known")

        expect:
        rule.evaluate(facts).decision() == decision

        where:
        amount  | recipient || decision
        100_000 | "new"     || Decision.APPROVE
        100_001 | "new"     || Decision.REVIEW
        100_001 | "known"   || Decision.APPROVE
        900_000 | "known"   || Decision.APPROVE
    }

    def "review says why"() {
        expect:
        rule.evaluate(new FraudFacts(100_001, 1, 0, 0, false)).reason() == "new recipient: 100001 is above 100000"
    }
}
