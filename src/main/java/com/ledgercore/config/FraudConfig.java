package com.ledgercore.config;

import java.time.Duration;

// amounts are in minor units and shared by EUR, GBP and USD, which all have two decimals
public record FraudConfig(
        int velocityMaxTransfers,
        Duration velocityWindow,
        long absoluteLimit,
        long anomalyMultiplier,
        int anomalyMinHistory,
        int anomalyHistorySize,
        long newRecipientLimit) {

    public static FraudConfig defaults() {
        return new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 5, 20, 100_000);
    }

    public static FraudConfig fromEnv() {
        FraudConfig d = defaults();
        return new FraudConfig(
                Env.optionalInt("FRAUD_VELOCITY_MAX_TRANSFERS", d.velocityMaxTransfers()),
                Duration.ofSeconds(Env.optionalLong("FRAUD_VELOCITY_WINDOW_SECONDS", d.velocityWindow().toSeconds())),
                Env.optionalLong("FRAUD_ABSOLUTE_LIMIT", d.absoluteLimit()),
                Env.optionalLong("FRAUD_ANOMALY_MULTIPLIER", d.anomalyMultiplier()),
                Env.optionalInt("FRAUD_ANOMALY_MIN_HISTORY", d.anomalyMinHistory()),
                Env.optionalInt("FRAUD_ANOMALY_HISTORY_SIZE", d.anomalyHistorySize()),
                Env.optionalLong("FRAUD_NEW_RECIPIENT_LIMIT", d.newRecipientLimit()));
    }
}
