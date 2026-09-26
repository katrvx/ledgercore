# Fraud rules evaluation

I ran my three fraud rules over 10,000 synthetic transfers with known labels and counted how many they catch.
Every number on this page comes from one run of:

```
./gradlew fraudEvaluation
```

The task uses seed 2 and the default thresholds from `FraudConfig.defaults()`. The same seed always gives the same data and the same numbers.

## Please read this first

The data is synthetic. I wrote the generator myself, and I wrote it knowing how the rules work. So these numbers show that the rules behave the way I designed them on the patterns I imagined. They do not tell how well the rules would detect real fraud. Real fraud adapts to the rules, and real honest customers are more varied than my generator.

## Results

A transfer counts as flagged when the engine returns REVIEW or DECLINE.

| | Flagged (REVIEW or DECLINE) | DECLINE only |
|---|---|---|
| Recall (share of fraud caught) | 18.2% | 13.0% |
| Precision (share of flagged that is fraud) | 17.8% | 44.2% |
| False positive rate (share of honest transfers flagged) | 4.4% | 0.9% |

Confusion matrix for flagged:

| | Flagged | Not flagged |
|---|---|---|
| Fraud | 91 | 410 |
| Honest | 421 | 9,078 |

## By kind of behaviour

| Kind | Fraud | Transfers | Review | Decline | Flagged |
|---|---|---|---|---|---|
| regular | no | 7,098 | 0 | 0 | 0.0% |
| new small | no | 879 | 0 | 0 | 0.0% |
| legit large | no | 348 | 339 | 0 | 97.4% |
| bill split | no | 1,174 | 0 | 82 | 7.0% |
| takeover burst | yes | 364 | 0 | 53 | 14.6% |
| drain | yes | 31 | 19 | 12 | 100.0% |
| mule | yes | 33 | 7 | 0 | 21.2% |
| low and slow | yes | 73 | 0 | 0 | 0.0% |

- regular: to one of 3 to 6 friends, 50% to 150% of the customer's typical amount
- new small: to a new account, at most the typical amount
- legit large: to a new account, 10 to 40 times the typical amount (rent, a car)
- bill split: 6 to 8 small transfers to friends within two minutes
- takeover burst: 6 to 10 transfers to new accounts, 5 to 20 seconds apart, 2 to 5 times the typical amount
- drain: one transfer of 1,500 to 15,000 EUR to a new account
- mule: 1 or 2 transfers of 3 to 8 times the typical amount to one new account
- low and slow: 3 to 5 transfers below the typical amount to one new account, days apart

## By rule

One transfer can be flagged by more than one rule.

| Rule | Fired on fraud | Fired on honest |
|---|---|---|
| velocity | 53 | 82 |
| amount | 31 | 280 |
| new recipient | 36 | 301 |

## Data

- 10,000 transfers from 300 customers over 30 days, 501 of them fraud (5.0%)
- each customer has a typical amount between 10 and 200 EUR
- fraud starts on day 5, so most victims already have some history

## Thresholds

I fixed these before the first run. I ran seed 1 once to check that the generator works. I saw its numbers too, and I did not change the rules, the thresholds or the generator after that.

| Setting | Value |
|---|---|
| Velocity: max transfers per window (DECLINE above) | 5 per 60 s |
| Absolute amount limit (DECLINE above) | 1,000,000 minor units |
| Amount anomaly (REVIEW) | more than 10 times the average of the last 20 completed transfers, with at least 5 of them |
| New recipient (REVIEW above) | 100,000 minor units |

## Limitations

- The data is synthetic and I made it knowing the rules, so the numbers are not real-world detection performance.
- The low and slow pattern, and most mule transfers, are built to stay under every threshold. I added them on purpose, so the report shows what the rules can't see.
- The simulation treats every approved transfer as completed. It has no balances, so it never sees a 422 for insufficient funds.
- The facts come from a simulator that copies the logic of `FraudFactsCollector`. Redis and SQL are tested separately in `FraudFactsCollectorSpec`, not here.
- This is one run with one seed, so there is no error margin.
