package com.ledgercore.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

// makes the same 10,000 labeled transfers for the same seed, amounts in minor units
public class TransactionGenerator {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final int DAYS = 30;
    private static final int CUSTOMERS = 300;
    private static final int TOTAL = 10_000;
    private static final int FRAUD_TARGET = TOTAL * 5 / 100;
    // fraud starts after a few days, so most victims already have some history
    private static final int FIRST_FRAUD_DAY = 5;
    private static final int SECONDS_PER_DAY = 86_400;

    private record Customer(long id, long typicalAmount, List<Long> friends) {
    }

    private final Random random;
    private final List<Customer> customers = new ArrayList<>();
    // accounts outside our customers: new shops, landlords, and the attackers' mule accounts
    private long nextOutsideId = 100_000;

    public TransactionGenerator(long seed) {
        this.random = new Random(seed);
    }

    public List<LabeledTransaction> generate() {
        createCustomers();
        List<LabeledTransaction> all = new ArrayList<>();
        int fraudCount = 0;
        while (fraudCount < FRAUD_TARGET) {
            int before = all.size();
            addFraudCase(all);
            fraudCount += all.size() - before;
        }
        while (all.size() < TOTAL) {
            addLegitCase(all, TOTAL - all.size());
        }
        // stable sort, so transfers with the same second keep the order they were made in
        all.sort(Comparator.comparing(LabeledTransaction::time));
        return all;
    }

    private void createCustomers() {
        for (long id = 1; id <= CUSTOMERS; id++) {
            long typical = between(1_000, 20_000);
            List<Long> friends = new ArrayList<>();
            int friendCount = (int) between(3, 6);
            while (friends.size() < friendCount) {
                long friend = between(1, CUSTOMERS);
                if (friend != id && !friends.contains(friend)) {
                    friends.add(friend);
                }
            }
            customers.add(new Customer(id, typical, friends));
        }
    }

    private void addLegitCase(List<LabeledTransaction> out, int room) {
        Customer c = randomCustomer();
        Instant time = randomTime(0, DAYS);
        int roll = random.nextInt(100);
        if (roll < 84) {
            out.add(legit(time, c.id(), randomFriend(c), percentOf(c.typicalAmount(), 50, 150), "regular"));
        } else if (roll < 94) {
            out.add(legit(time, c.id(), nextOutsideId++, between(100, c.typicalAmount()), "new small"));
        } else if (roll < 98) {
            // rent, a car, a holiday: honest but unusual
            out.add(legit(time, c.id(), nextOutsideId++, percentOf(c.typicalAmount(), 1_000, 4_000), "legit large"));
        } else if (room >= 8) {
            // paying friends back after a dinner, several transfers within two minutes
            int count = (int) between(6, 8);
            for (int i = 0; i < count; i++) {
                Instant at = time.plusSeconds(between(0, 120));
                out.add(legit(at, c.id(), randomFriend(c), percentOf(c.typicalAmount(), 20, 60), "bill split"));
            }
        }
    }

    private void addFraudCase(List<LabeledTransaction> out) {
        Customer victim = randomCustomer();
        Instant time = randomTime(FIRST_FRAUD_DAY, DAYS);
        int roll = random.nextInt(100);
        if (roll < 35) {
            // a stolen account emptied quickly to many new accounts
            int count = (int) between(6, 10);
            Instant at = time;
            for (int i = 0; i < count; i++) {
                at = at.plusSeconds(between(5, 20));
                out.add(fraud(at, victim.id(), nextOutsideId++, percentOf(victim.typicalAmount(), 200, 500), "takeover burst"));
            }
        } else if (roll < 60) {
            // one big transfer to a new account
            out.add(fraud(time, victim.id(), nextOutsideId++, between(150_000, 1_500_000), "drain"));
        } else if (roll < 80) {
            // a scam victim sends a few times the usual amount to one new account
            long mule = nextOutsideId++;
            int count = (int) between(1, 2);
            for (int i = 0; i < count; i++) {
                out.add(fraud(time.plusSeconds(i * 3_600L), victim.id(), mule, percentOf(victim.typicalAmount(), 300, 800), "mule"));
            }
        } else {
            // small amounts over several days, made to look normal
            long mule = nextOutsideId++;
            int count = (int) between(3, 5);
            Instant at = time;
            for (int i = 0; i < count; i++) {
                out.add(fraud(at, victim.id(), mule, percentOf(victim.typicalAmount(), 30, 90), "low and slow"));
                at = at.plusSeconds(between(SECONDS_PER_DAY, 3L * SECONDS_PER_DAY));
            }
        }
    }

    private LabeledTransaction legit(Instant time, long from, long to, long amount, String kind) {
        return new LabeledTransaction(time, from, to, amount, false, kind);
    }

    private LabeledTransaction fraud(Instant time, long from, long to, long amount, String kind) {
        return new LabeledTransaction(time, from, to, amount, true, kind);
    }

    private Customer randomCustomer() {
        return customers.get(random.nextInt(customers.size()));
    }

    private long randomFriend(Customer c) {
        return c.friends().get(random.nextInt(c.friends().size()));
    }

    private Instant randomTime(int fromDay, int toDay) {
        long seconds = between((long) fromDay * SECONDS_PER_DAY, (long) toDay * SECONDS_PER_DAY - 1);
        return START.plusSeconds(seconds);
    }

    // amount * percent / 100, kept in whole minor units
    private long percentOf(long amount, int minPercent, int maxPercent) {
        return Math.max(1, amount * between(minPercent, maxPercent) / 100);
    }

    private long between(long min, long max) {
        return min + random.nextLong(max - min + 1);
    }
}
