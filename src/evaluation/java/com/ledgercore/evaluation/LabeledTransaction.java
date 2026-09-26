package com.ledgercore.evaluation;

import java.time.Instant;

// one synthetic transfer with the truth attached: is it fraud, and what kind of behaviour made it
public record LabeledTransaction(Instant time, long fromAccountId, long toAccountId, long amount, boolean fraud,
                                 String kind) {
}
