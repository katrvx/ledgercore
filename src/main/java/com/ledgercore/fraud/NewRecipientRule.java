package com.ledgercore.fraud;

// a large first payment to someone new is the typical scam pattern
public class NewRecipientRule implements FraudRule {

    private final long limit;

    public NewRecipientRule(long limit) {
        this.limit = limit;
    }

    @Override
    public RuleResult evaluate(FraudFacts facts) {
        if (!facts.knownRecipient() && facts.amount() > limit) {
            return new RuleResult(Decision.REVIEW, "new recipient: " + facts.amount() + " is above " + limit);
        }
        return RuleResult.approve();
    }
}
