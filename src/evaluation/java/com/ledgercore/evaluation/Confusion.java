package com.ledgercore.evaluation;

// counts of the four outcomes, flagged means REVIEW or DECLINE
public class Confusion {

    private long truePositives;
    private long falsePositives;
    private long trueNegatives;
    private long falseNegatives;

    public void add(boolean fraud, boolean flagged) {
        if (fraud && flagged) {
            truePositives++;
        } else if (fraud) {
            falseNegatives++;
        } else if (flagged) {
            falsePositives++;
        } else {
            trueNegatives++;
        }
    }

    // share of fraud that was caught
    public double recall() {
        return ratio(truePositives, truePositives + falseNegatives);
    }

    // share of flagged transfers that really were fraud
    public double precision() {
        return ratio(truePositives, truePositives + falsePositives);
    }

    // share of honest transfers that were flagged anyway
    public double falsePositiveRate() {
        return ratio(falsePositives, falsePositives + trueNegatives);
    }

    public long truePositives() {
        return truePositives;
    }

    public long falsePositives() {
        return falsePositives;
    }

    public long trueNegatives() {
        return trueNegatives;
    }

    public long falseNegatives() {
        return falseNegatives;
    }

    private double ratio(long part, long whole) {
        if (whole == 0) {
            return 0;
        }
        return (double) part / whole;
    }
}
