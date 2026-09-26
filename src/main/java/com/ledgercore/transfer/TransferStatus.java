package com.ledgercore.transfer;

public enum TransferStatus {
    COMPLETED,
    // flagged by the fraud rules, no money has moved
    PENDING_REVIEW,
    // blocked by the fraud rules, kept for audit
    DECLINED
}
