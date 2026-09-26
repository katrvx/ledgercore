package com.ledgercore.fraud;

public record RuleResult(Decision decision, String reason) {

    public static RuleResult approve() {
        return new RuleResult(Decision.APPROVE, null);
    }
}
