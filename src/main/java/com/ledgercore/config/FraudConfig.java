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

    // a zero or negative limit would switch a rule off or flag everything, so it is refused
    public FraudConfig {
        requirePositive(velocityMaxTransfers, "velocityMaxTransfers");
        requirePositive(velocityWindow.toMillis(), "velocityWindow");
        requirePositive(absoluteLimit, "absoluteLimit");
        requirePositive(anomalyMultiplier, "anomalyMultiplier");
        requirePositive(anomalyMinHistory, "anomalyMinHistory");
        requirePositive(anomalyHistorySize, "anomalyHistorySize");
        requirePositive(newRecipientLimit, "newRecipientLimit");
    }

    public static FraudConfig defaults() {
        return new FraudConfig(5, Duration.ofSeconds(60), 1_000_000, 10, 5, 20, 100_000);
    }

    public static FraudConfig from(Env env) {
        FraudConfig d = defaults();
        return new FraudConfig(
                env.positiveInt("FRAUD_VELOCITY_MAX_TRANSFERS", d.velocityMaxTransfers()),
                Duration.ofSeconds(env.positiveLong("FRAUD_VELOCITY_WINDOW_SECONDS", d.velocityWindow().toSeconds())),
                env.positiveLong("FRAUD_ABSOLUTE_LIMIT", d.absoluteLimit()),
                env.positiveLong("FRAUD_ANOMALY_MULTIPLIER", d.anomalyMultiplier()),
                env.positiveInt("FRAUD_ANOMALY_MIN_HISTORY", d.anomalyMinHistory()),
                env.positiveInt("FRAUD_ANOMALY_HISTORY_SIZE", d.anomalyHistorySize()),
                env.positiveLong("FRAUD_NEW_RECIPIENT_LIMIT", d.newRecipientLimit()));
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
