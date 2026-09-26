package com.ledgercore.fraud;

public class AmountAnomalyRule implements FraudRule {

    private final long absoluteLimit;
    private final long multiplier;
    private final int minHistory;

    public AmountAnomalyRule(long absoluteLimit, long multiplier, int minHistory) {
        this.absoluteLimit = absoluteLimit;
        this.multiplier = multiplier;
        this.minHistory = minHistory;
    }

    @Override
    public RuleResult evaluate(FraudFacts facts) {
        long amount = facts.amount();
        if (amount > absoluteLimit) {
            return new RuleResult(Decision.DECLINE,
                    "amount: " + amount + " is above the absolute limit " + absoluteLimit);
        }
        // with too little history the usual amount means nothing
        if (facts.historySize() >= minHistory && farAboveUsual(amount, facts.usualAmount())) {
            return new RuleResult(Decision.REVIEW,
                    "amount: " + amount + " is more than " + multiplier + " times the usual " + facts.usualAmount());
        }
        return RuleResult.approve();
    }

    private boolean farAboveUsual(long amount, long usual) {
        long limit;
        try {
            limit = Math.multiplyExact(usual, multiplier);
        } catch (ArithmeticException e) {
            // the limit is bigger than any long, so no amount can be above it
            return false;
        }
        return amount > limit;
    }
}
