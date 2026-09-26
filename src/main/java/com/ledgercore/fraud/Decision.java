package com.ledgercore.fraud;

// ordered from the mildest to the strictest, so compareTo tells which one wins
public enum Decision {
    APPROVE,
    REVIEW,
    DECLINE
}
