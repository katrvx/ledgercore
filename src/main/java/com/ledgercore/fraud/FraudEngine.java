package com.ledgercore.fraud;

import com.ledgercore.config.FraudConfig;

import java.util.ArrayList;
import java.util.List;

public class FraudEngine {

    private final List<FraudRule> rules;

    public FraudEngine(List<FraudRule> rules) {
        this.rules = rules;
    }

    // the app and the offline evaluation build exactly the same rules
    public static FraudEngine fromConfig(FraudConfig config) {
        return new FraudEngine(List.of(
                new VelocityRule(config.velocityMaxTransfers()),
                new AmountAnomalyRule(config.absoluteLimit(), config.anomalyMultiplier(), config.anomalyMinHistory()),
                new NewRecipientRule(config.newRecipientLimit())));
    }

    // runs every rule, the strictest decision wins and all reasons are kept
    public RuleResult evaluate(FraudFacts facts) {
        Decision decision = Decision.APPROVE;
        List<String> reasons = new ArrayList<>();
        for (FraudRule rule : rules) {
            RuleResult result = rule.evaluate(facts);
            if (result.decision() != Decision.APPROVE) {
                reasons.add(result.reason());
            }
            if (result.decision().compareTo(decision) > 0) {
                decision = result.decision();
            }
        }
        if (reasons.isEmpty()) {
            return RuleResult.approve();
        }
        return new RuleResult(decision, String.join("; ", reasons));
    }
}
