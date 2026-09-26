package com.ledgercore.evaluation;

import com.ledgercore.config.FraudConfig;
import com.ledgercore.fraud.Decision;
import com.ledgercore.fraud.FraudEngine;
import com.ledgercore.fraud.RuleResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// runs the real fraud rules over synthetic labeled transfers and writes a markdown report
public class FraudEvaluation {

    private static final List<String> LEGIT_KINDS = List.of("regular", "new small", "legit large", "bill split");
    private static final List<String> FRAUD_KINDS = List.of("takeover burst", "drain", "mule", "low and slow");
    private static final List<String> RULES = List.of("velocity", "amount", "new recipient");

    private static class KindStats {
        long count;
        long reviewed;
        long declined;
    }

    private static class RuleStats {
        long onFraud;
        long onLegit;
    }

    public static void main(String[] args) throws IOException {
        long seed = Long.parseLong(args[0]);
        Path output = Path.of(args[1]);
        FraudConfig config = FraudConfig.defaults();

        List<LabeledTransaction> transactions = new TransactionGenerator(seed).generate();
        FraudEngine engine = FraudEngine.fromConfig(config);
        FactsSimulator simulator = new FactsSimulator(config);

        Confusion flagged = new Confusion();
        Confusion declinedOnly = new Confusion();
        Map<String, KindStats> kinds = new LinkedHashMap<>();
        for (String kind : LEGIT_KINDS) {
            kinds.put(kind, new KindStats());
        }
        for (String kind : FRAUD_KINDS) {
            kinds.put(kind, new KindStats());
        }
        Map<String, RuleStats> rules = new LinkedHashMap<>();
        for (String rule : RULES) {
            rules.put(rule, new RuleStats());
        }

        for (LabeledTransaction tx : transactions) {
            RuleResult result = engine.evaluate(simulator.factsFor(tx));
            simulator.record(tx, result.decision());

            flagged.add(tx.fraud(), result.decision() != Decision.APPROVE);
            declinedOnly.add(tx.fraud(), result.decision() == Decision.DECLINE);
            KindStats stats = kinds.get(tx.kind());
            stats.count++;
            if (result.decision() == Decision.REVIEW) {
                stats.reviewed++;
            }
            if (result.decision() == Decision.DECLINE) {
                stats.declined++;
            }
            countRules(rules, result, tx.fraud());
        }

        String report = render(seed, config, transactions, flagged, declinedOnly, kinds, rules);
        Files.writeString(output, report);
        System.out.println(summary(seed, transactions, flagged, declinedOnly));
        System.out.println("report written to " + output);
    }

    // a reason looks like "velocity: ...; new recipient: ...", the part before the colon names the rule
    private static void countRules(Map<String, RuleStats> rules, RuleResult result, boolean fraud) {
        if (result.reason() == null) {
            return;
        }
        for (String part : result.reason().split("; ")) {
            RuleStats stats = rules.get(part.substring(0, part.indexOf(':')));
            if (fraud) {
                stats.onFraud++;
            } else {
                stats.onLegit++;
            }
        }
    }

    private static String summary(long seed, List<LabeledTransaction> transactions, Confusion flagged, Confusion declinedOnly) {
        long fraud = transactions.stream().filter(LabeledTransaction::fraud).count();
        return "seed=" + seed + " transactions=" + transactions.size() + " fraud=" + fraud
                + "\nflagged (REVIEW or DECLINE): tp=" + flagged.truePositives() + " fp=" + flagged.falsePositives()
                + " tn=" + flagged.trueNegatives() + " fn=" + flagged.falseNegatives()
                + " recall=" + percent(flagged.recall()) + " precision=" + percent(flagged.precision())
                + " fpr=" + percent(flagged.falsePositiveRate())
                + "\nDECLINE only: tp=" + declinedOnly.truePositives() + " fp=" + declinedOnly.falsePositives()
                + " recall=" + percent(declinedOnly.recall()) + " precision=" + percent(declinedOnly.precision())
                + " fpr=" + percent(declinedOnly.falsePositiveRate());
    }

    private static String render(long seed, FraudConfig config, List<LabeledTransaction> transactions,
                                 Confusion flagged, Confusion declinedOnly,
                                 Map<String, KindStats> kinds, Map<String, RuleStats> rules) {
        long fraud = transactions.stream().filter(LabeledTransaction::fraud).count();
        StringBuilder md = new StringBuilder();
        md.append("# Fraud rules evaluation\n\n");
        md.append("I ran my three fraud rules over ").append(number(transactions.size()))
                .append(" synthetic transfers with known labels and counted how many they catch.\n");
        md.append("Every number on this page comes from one run of:\n\n");
        md.append("```\n./gradlew fraudEvaluation\n```\n\n");
        md.append("The task uses seed ").append(seed).append(" and the default thresholds from `FraudConfig.defaults()`. ");
        md.append("The same seed always gives the same data and the same numbers.\n\n");

        md.append("## Please read this first\n\n");
        md.append("The data is synthetic. I wrote the generator myself, and I wrote it knowing how the rules work. ");
        md.append("So these numbers show that the rules behave the way I designed them on the patterns I imagined. ");
        md.append("They do not tell how well the rules would detect real fraud. ");
        md.append("Real fraud adapts to the rules, and real honest customers are more varied than my generator.\n\n");

        md.append("## Results\n\n");
        md.append("A transfer counts as flagged when the engine returns REVIEW or DECLINE.\n\n");
        md.append("| | Flagged (REVIEW or DECLINE) | DECLINE only |\n|---|---|---|\n");
        md.append("| Recall (share of fraud caught) | ").append(percent(flagged.recall())).append(" | ")
                .append(percent(declinedOnly.recall())).append(" |\n");
        md.append("| Precision (share of flagged that is fraud) | ").append(percent(flagged.precision())).append(" | ")
                .append(percent(declinedOnly.precision())).append(" |\n");
        md.append("| False positive rate (share of honest transfers flagged) | ").append(percent(flagged.falsePositiveRate()))
                .append(" | ").append(percent(declinedOnly.falsePositiveRate())).append(" |\n\n");

        md.append("Confusion matrix for flagged:\n\n");
        md.append("| | Flagged | Not flagged |\n|---|---|---|\n");
        md.append("| Fraud | ").append(number(flagged.truePositives())).append(" | ")
                .append(number(flagged.falseNegatives())).append(" |\n");
        md.append("| Honest | ").append(number(flagged.falsePositives())).append(" | ")
                .append(number(flagged.trueNegatives())).append(" |\n\n");

        md.append("## By kind of behaviour\n\n");
        md.append("| Kind | Fraud | Transfers | Review | Decline | Flagged |\n|---|---|---|---|---|---|\n");
        for (Map.Entry<String, KindStats> entry : kinds.entrySet()) {
            KindStats s = entry.getValue();
            boolean isFraud = FRAUD_KINDS.contains(entry.getKey());
            md.append("| ").append(entry.getKey()).append(" | ").append(isFraud ? "yes" : "no").append(" | ")
                    .append(number(s.count)).append(" | ").append(number(s.reviewed)).append(" | ")
                    .append(number(s.declined)).append(" | ").append(percent(ratio(s.reviewed + s.declined, s.count)))
                    .append(" |\n");
        }
        md.append("\n");
        md.append("- regular: to one of 3 to 6 friends, 50% to 150% of the customer's typical amount\n");
        md.append("- new small: to a new account, at most the typical amount\n");
        md.append("- legit large: to a new account, 10 to 40 times the typical amount (rent, a car)\n");
        md.append("- bill split: 6 to 8 small transfers to friends within two minutes\n");
        md.append("- takeover burst: 6 to 10 transfers to new accounts, 5 to 20 seconds apart, 2 to 5 times the typical amount\n");
        md.append("- drain: one transfer of 1,500 to 15,000 EUR to a new account\n");
        md.append("- mule: 1 or 2 transfers of 3 to 8 times the typical amount to one new account\n");
        md.append("- low and slow: 3 to 5 transfers below the typical amount to one new account, days apart\n\n");

        md.append("## By rule\n\n");
        md.append("One transfer can be flagged by more than one rule.\n\n");
        md.append("| Rule | Fired on fraud | Fired on honest |\n|---|---|---|\n");
        for (Map.Entry<String, RuleStats> entry : rules.entrySet()) {
            md.append("| ").append(entry.getKey()).append(" | ").append(number(entry.getValue().onFraud)).append(" | ")
                    .append(number(entry.getValue().onLegit)).append(" |\n");
        }
        md.append("\n");

        md.append("## Data\n\n");
        md.append("- ").append(number(transactions.size())).append(" transfers from 300 customers over 30 days, ")
                .append(number(fraud)).append(" of them fraud (").append(percent(ratio(fraud, transactions.size()))).append(")\n");
        md.append("- each customer has a typical amount between 10 and 200 EUR\n");
        md.append("- fraud starts on day 5, so most victims already have some history\n\n");

        md.append("## Thresholds\n\n");
        md.append("I fixed these before the first run. ");
        md.append("I ran seed 1 once to check that the generator works. I saw its numbers too, ");
        md.append("and I did not change the rules, the thresholds or the generator after that.\n\n");
        md.append("| Setting | Value |\n|---|---|\n");
        md.append("| Velocity: max transfers per window (DECLINE above) | ").append(config.velocityMaxTransfers()).append(" per ")
                .append(config.velocityWindow().toSeconds()).append(" s |\n");
        md.append("| Absolute amount limit (DECLINE above) | ").append(number(config.absoluteLimit())).append(" minor units |\n");
        md.append("| Amount anomaly (REVIEW) | more than ").append(config.anomalyMultiplier())
                .append(" times the average of the last ").append(config.anomalyHistorySize())
                .append(" completed transfers, with at least ").append(config.anomalyMinHistory()).append(" of them |\n");
        md.append("| New recipient (REVIEW above) | ").append(number(config.newRecipientLimit())).append(" minor units |\n\n");

        md.append("## Limitations\n\n");
        md.append("- The data is synthetic and I made it knowing the rules, so the numbers are not real-world detection performance.\n");
        md.append("- The low and slow pattern, and most mule transfers, are built to stay under every threshold. ");
        md.append("I added them on purpose, so the report shows what the rules can't see.\n");
        md.append("- The simulation treats every approved transfer as completed. It has no balances, so it never sees a 422 for insufficient funds.\n");
        md.append("- The facts come from a simulator that copies the logic of `FraudFactsCollector`. ");
        md.append("Redis and SQL are tested separately in `FraudFactsCollectorSpec`, not here.\n");
        md.append("- This is one run with one seed, so there is no error margin.\n");
        return md.toString();
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
