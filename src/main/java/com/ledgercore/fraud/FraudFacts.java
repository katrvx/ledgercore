package com.ledgercore.fraud;

// everything the rules need to know about one transfer, so the rules themselves never touch redis or the database
public record FraudFacts(long amount, int attemptsInWindow, int historySize, long usualAmount, boolean knownRecipient) {
}
