package com.ledgercore.fraud;

// many transfers in a short time looks like a script or a stolen account
public class VelocityRule implements FraudRule {

    private final int maxTransfers;

    public VelocityRule(int maxTransfers) {
        this.maxTransfers = maxTransfers;
    }

    @Override
    public RuleResult evaluate(FraudFacts facts) {
        if (facts.attemptsInWindow() > maxTransfers) {
            return new RuleResult(Decision.DECLINE,
                    "velocity: " + facts.attemptsInWindow() + " transfers in the window, limit " + maxTransfers);
        }
        return RuleResult.approve();
    }
}
