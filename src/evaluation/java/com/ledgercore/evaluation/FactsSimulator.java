package com.ledgercore.evaluation;

import com.ledgercore.config.FraudConfig;
import com.ledgercore.fraud.Decision;
import com.ledgercore.fraud.FraudFacts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// builds the same facts as FraudFactsCollector, from memory instead of redis and postgres
public class FactsSimulator {

    private final FraudConfig config;
    private final Map<Long, List<Instant>> attempts = new HashMap<>();
    // oldest first
    private final Map<Long, List<Long>> completedAmounts = new HashMap<>();
    private final Map<Long, Set<Long>> knownRecipients = new HashMap<>();

    public FactsSimulator(FraudConfig config) {
        this.config = config;
    }

    public FraudFacts factsFor(LabeledTransaction tx) {
        long from = tx.fromAccountId();
        Instant windowStart = tx.time().minus(config.velocityWindow());
        // this attempt counts too, like the ZADD before ZCARD in redis
        int inWindow = 1;
        for (Instant attempt : attempts.getOrDefault(from, List.of())) {
            if (attempt.isAfter(windowStart)) {
                inWindow++;
            }
        }
        List<Long> history = lastCompleted(from);
        boolean known = knownRecipients.getOrDefault(from, Set.of()).contains(tx.toAccountId());
        return new FraudFacts(tx.amount(), inWindow, history.size(), average(history), known);
    }

    // every attempt counts for velocity, only approved ones become history
    public void record(LabeledTransaction tx, Decision decision) {
        long from = tx.fromAccountId();
        attempts.computeIfAbsent(from, k -> new ArrayList<>()).add(tx.time());
        if (decision == Decision.APPROVE) {
            completedAmounts.computeIfAbsent(from, k -> new ArrayList<>()).add(tx.amount());
            knownRecipients.computeIfAbsent(from, k -> new HashSet<>()).add(tx.toAccountId());
        }
    }

    private List<Long> lastCompleted(long accountId) {
        List<Long> all = completedAmounts.getOrDefault(accountId, List.of());
        int from = Math.max(0, all.size() - config.anomalyHistorySize());
        return all.subList(from, all.size());
    }

    private long average(List<Long> amounts) {
        if (amounts.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (long amount : amounts) {
            sum += amount;
        }
        return sum / amounts.size();
    }
}
