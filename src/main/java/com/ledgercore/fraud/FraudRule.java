package com.ledgercore.fraud;

public interface FraudRule {

    RuleResult evaluate(FraudFacts facts);
}
