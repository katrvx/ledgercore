# Load test

I load tested `POST /transfers` with Gatling on my laptop. I wanted to know three things: how many transfers per second the service handles, how fast it answers at a normal load, and what locking costs when all transfers fight over the same few accounts. Every number here comes from `scripts/load-test.sh`.

The test also found a real problem: the fraud check called Redis while its database transaction was open, and a slow Redis call then blocked the connection pool. I changed that. The section "What caused the p99 spikes" shows how I found it and the same run before and after the change. All the other tables are measured on the current code, after the change.

## The machine and the settings

Everything ran on one laptop: Gatling, the app, Postgres and Redis. They share the same 8 cores, so these numbers are a lower bound for this laptop, not what a real server would do.

| | |
|---|---|
| Laptop | Apple M1, 8 cores, 8 GB RAM, macOS 14.2.1 (Darwin 23.2.0) |
| Docker | Docker Desktop 29.8.0, VM with 8 CPUs and 3.8 GiB memory, no limits on the containers |
| Postgres | 18.6 in Docker, default settings |
| Redis | 7.4.11 in Docker, default settings |
| App JVM | Temurin 21.0.12.1, `-Xms512m -Xmx512m`, G1 (the default) |
| App settings | Hikari pool of 10 connections (the default), Spark and Jetty defaults |
| Gatling | 3.15, JVM with the Gradle plugin defaults (`-Xmx1G`) |

The app runs directly on macOS and reaches Postgres and Redis through Docker Desktop's port forwarding.

The fraud rules still run on every transfer: they read Redis and Postgres as usual. Their limits are set so high through `FRAUD_*` environment variables that no load test transfer can trip them. With the default limits the velocity rule would decline almost everything, and I would be measuring declines.

## How I measured

- Open model: new requests arrive at a fixed rate, however fast the service answers. A closed model slows down together with the service and hides queueing.
- Before each run, the simulation creates the accounts and funds them with a normal HTTP client. That setup is not in the numbers.
- Every transfer goes between two random accounts, for 1 to 100 cents, with a new `Idempotency-Key`.
- Before each measured run there is a 30 second warm-up at 20 requests per second, in a separate Gatling run, so JIT compilation does not skew the numbers.
- The load test uses its own Postgres and Redis (compose project `ledgercore-load` on other ports) and deletes them at the end.
- While the test runs, the script also records GC pauses, pool stats, `pg_stat_activity`, Redis round trips and CPU (see the section on the spikes). This adds a little work, the same in every run.
- After every run, a SQL check makes sure no money appeared or disappeared (see below).
- Achieved throughput is Gatling's mean: all requests divided by the whole run time in whole seconds. 30,000 requests over 121 seconds show as 247.93/s, even though requests arrived at 250/s.

## Results

### Capacity: 1,000 accounts, 30 seconds per rate, one run each

| Target rate | Achieved | Requests | Failed | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|
| 50/s | 50/s | 1,500 | 0 | 9 ms | 16 ms | 27 ms | 147 ms |
| 100/s | 100/s | 3,000 | 0 | 6 ms | 11 ms | 23 ms | 72 ms |
| 200/s | 200/s | 6,000 | 0 | 4 ms | 15 ms | 58 ms | 129 ms |
| 300/s | 300/s | 9,000 | 0 | 4 ms | 25 ms | 117 ms | 252 ms |
| 400/s | 400/s | 12,000 | 0 | 4 ms | 61 ms | 178 ms | 319 ms |
| 500/s | 483.87/s | 15,000 | 0 | 4 ms | 18 ms | 39 ms | 149 ms |
| 600/s | 486.49/s | 18,000 | 0 | 357 ms | 9,305 ms | 13,278 ms | 15,478 ms |
| 700/s | 355.93/s | 21,000 | 0 | 8,901 ms | 34,239 ms | 34,448 ms | 34,832 ms |
| 800/s | 705.88/s | 24,000 | 0 | 360 ms | 7,888 ms | 12,230 ms | 13,987 ms |
| 1,000/s | 681.82/s | 30,000 | 0 | 6,853 ms | 20,947 ms | 21,818 ms | 22,446 ms |

What I read from it:
- The service kept up with the rate up to 500/s. At 600/s it fell behind and requests queued for seconds, so on this laptop the limit is between 500 and 600 transfers per second.
- Above the limit the numbers jump around (356/s at 700/s, 706/s at 800/s). When everything is queueing on one laptop, small things decide how much gets through, so I don't read a single maximum from these rows.
- No request failed at any rate, and the server returned no errors.
- One 30 second run per rate is only a rough first look. 30 seconds is also short to see if a queue drains: 400/s ran for 120 seconds in the before and after comparison below, and it was fine on the current code.

### Steady load: 1,000 accounts, 250/s, 3 runs of 120 seconds

| Run | Achieved | Requests | Failed | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|
| 1 | 250/s | 30,000 | 0 | 4 ms | 9 ms | 23 ms | 90 ms |
| 2 | 250/s | 30,000 | 0 | 4 ms | 8 ms | 23 ms | 88 ms |
| 3 | 247.93/s | 30,000 | 0 | 4 ms | 8 ms | 19 ms | 94 ms |
| **Median (range)** | 250/s (247.93 to 250) | | 0% | 4 ms (4 to 4) | 8 ms (8 to 9) | 23 ms (19 to 23) | 90 ms (88 to 94) |

250/s is a normal working load with room to spare: about half of the limit. None of the 363 seconds in these three runs had a server side p99 over 100 ms.

### Contention: 10 accounts, 250/s, 3 runs of 120 seconds

Every transfer locks two of only 10 rows, so transfers wait for each other's locks all the time.

| Run | Achieved | Requests | Failed | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|
| 1 | 250/s | 30,000 | 0 | 4 ms | 9 ms | 29 ms | 187 ms |
| 2 | 247.93/s | 30,000 | 0 | 4 ms | 8 ms | 23 ms | 121 ms |
| 3 | 247.93/s | 30,000 | 0 | 4 ms | 10 ms | 29 ms | 143 ms |
| **Median (range)** | 247.93/s (247.93 to 250) | | 0% | 4 ms (4 to 4) | 9 ms (8 to 10) | 29 ms (23 to 29) | 143 ms (121 to 187) |

At 250/s, contention costs a little in the tail: the p99 median is 29 ms instead of 23 ms, and the median transfer is just as fast. Each of the 10 accounts is in about 50 transfers per second, and a lock is held for a few milliseconds, so a row is free most of the time. Pessimistic locking would start to hurt when one account gets close to 1 second divided by the lock time, a few hundred transfers per second on a single account. I did not test that point.

### No money appeared or disappeared

After each of the 20 runs in this report (16 on the current code and the 4 before and after runs), including the overloaded ones, this check returned 0 for every value:
- the sum of all ledger entries;
- the sum of all balances (funding accounts included);
- ledger entries minus 2 × completed transfers;
- customer accounts with a negative balance;
- accounts whose balance is not the sum of their own ledger entries.

The server handled 523,495 transfers in these runs, warm-ups included, and all of them returned 201. The server never logged an error.

## What caused the p99 spikes

This section is about the code before the change. I first measured the service with the fraud check calling Redis inside the transfer's transaction, and the p99 had spikes of seconds, even at 250/s in some runs.

To explain them I ran the app with extra logging, only in the load test (see `scripts/load-test.sh`):
- GC pauses from `-Xlog:gc`;
- Hikari pool stats every 2 seconds;
- every autovacuum and autoanalyze from Postgres (`log_autovacuum_min_duration = 0`, only in the throwaway load test database);
- Postgres checkpoints;
- `pg_stat_activity` every 0.5 seconds: what each connection of the app is doing;
- a Redis `PING` from the host 20 times per second, over the same Docker port forward the app uses;
- CPU of the app, Gatling, Postgres and Redis every 2 to 3 seconds.

`scripts/analyze-load-test.py` takes each second of a run, calls it slow when the server side p99 in that second is over 100 ms, and checks what else happened then. To see if a match means anything, it also shows the same thing for normal seconds.

### The mechanism: the pool queue

Every slow second in every run had request threads waiting for one of the 10 pool connections. Something made a few transactions slow, they kept their connections, and up to 185 requests queued behind them.

### What I ruled out first

- GC: only 17% of the slow seconds had a pause of 20 ms or more nearby, and the longest pause below overload was 31 ms. A 31 ms pause can't explain a second-long queue.
- Autovacuum: 0% of the slow seconds at 400/s and 500/s. Below overload each autovacuum lasted at most 0.31 s, and they fell in normal seconds.
- Checkpoints alone: whole runs lay inside one spread out checkpoint, fast seconds included.
- The laptop running out of CPU: at the worst moments everything together used about 2.7 of the 8 cores.

### What the connections were doing

At 400/s for 120 seconds, before the change (mean number of the 10 connections in each state):

| | Slow seconds | Normal seconds |
|---|---|---|
| `idle in transaction`: Postgres waits for the app | 6.2 | 1.0 |
| waiting for the WAL on disk (`IO/WalSync`, `LWLock/WALWrite`) | 0.7 | 0.0 |
| running on CPU | 0.5 | 0.3 |
| Redis round trip from the host, slowest in the second | 47 ms | 4 ms |

The first run looked the same (6.1 connections idle in transaction). So in the slow seconds most connections were not busy in Postgres at all: a transaction was open and Postgres was waiting for the app to send the next statement. At the same moments Redis round trips were ten times slower, once 1,459 ms.

That points at the fraud velocity check. It sent 4 Redis commands (`ZADD`, `ZREMRANGEBYSCORE`, `ZCARD`, `PEXPIRE`) while the transfer's transaction already held a connection. When Redis answered in 50 ms instead of 1 ms, every transfer kept its connection about 200 ms longer, and 10 connections were not enough for 400 transfers per second.

It is not the WAL `fsync`: the disk waits were a tenth of the time spent idle in a transaction.

### The change

The velocity count now runs before the database transaction opens (`FraudFactsCollector.recordAttempt`, called from `TransferService.recordAttempt`). The transaction only does database work. A spec pauses Redis during a transfer and checks `pg_stat_activity`: before the change a transaction stayed idle for 538 ms, now it is under 200 ms (`RedisOutsideTransactionSpec`).

### Before and after, same setup

1,000 accounts, 400/s, 2 runs of 120 seconds each, the same warm-up and the same diagnostics. The before and after runs were about 20 minutes apart on the same laptop.

| | Achieved | Requests | Failed | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|
| before the change, run 1 | 350.36/s | 48,000 | 5 | 13,048 ms | 27,755 ms | 40,422 ms | 51,321 ms |
| before the change, run 2 | 396.69/s | 48,000 | 0 | 851 ms | 14,964 ms | 24,401 ms | 24,984 ms |
| after the change, run 1 | 396.69/s | 48,000 | 0 | 4 ms | 47 ms | 127 ms | 396 ms |
| after the change, run 2 | 396.69/s | 48,000 | 0 | 4 ms | 21 ms | 50 ms | 193 ms |

- Before, a stall turned into a queue the service never caught up with: requests waited up to 51 seconds. The 5 failures were `Premature close`: Jetty closed those connections while the requests were still queued (its idle timeout is 30 seconds). The server logged no error, and the money checks were all 0.
- After, the most threads waiting for the pool at once went down from 185 to 12. In the slow seconds, the slowest Redis round trip went from 58 and 47 ms (before, runs 1 and 2) to 7 and 2 ms (after). The slower Redis before was probably partly a result of the overload too: 185 waiting threads and a busy CPU slow down everything on the laptop.

### What is left

- At 250/s on the current code, none of the 363 seconds in the steady runs was slow, and 3 of 362 in the contention runs.
- At 400/s a few slow seconds are left: 3 of 31 in the capacity run, 12 and 2 in the two 120 second runs. In them, most connections are still `idle in transaction` (3.1 to 4.4 of 10), and some wait for the WAL (0.2 to 2.0, `IO/WalSync` and `LWLock/WALWrite`), which is Postgres writing its log to the Docker Desktop virtual disk. It is a small version of what happens past the limit (next point). I did not change anything for it. `synchronous_commit = off` would remove the WAL waits, but it can lose transfers that were already confirmed if Postgres crashes, so it is not an option for a ledger.
- Past the limit, at 600/s, the connections are again mostly `idle in transaction` (5.3 of 10 in slow seconds), even though Redis is no longer inside the transaction. At the same time the Redis round trip went from 3 to 36 ms, Postgres used almost 2 cores and 45% of the slow seconds had a GC pause of 20 ms or more nearby. A transfer makes about 10 round trips to Postgres, and on this laptop each of them goes through Docker Desktop's port forwarding, like the Redis ping. When the laptop is overloaded, that path slows down, and every transaction holds its connection longer. That is a limit of this setup more than of the code: in a real deployment the app and the database talk over a normal network, without the port forward.

## Limits of this test

- One laptop. The load generator takes CPU and memory from the same machine as the service.
- In an earlier series on the old code, 1,000/s produced 3,681 failed requests. All of them were `Too many open files` inside the Gatling JVM: macOS allows one process 10,240 open files, and each queued user held a connection. The server returned zero errors then too. On the current code no request failed at any rate.
- Docker Desktop runs Postgres and Redis in a Linux VM, and its virtual disk, scheduling and port forwarding are not like a real server's.
- The databases are fresh for every script run and grow from zero, so this doesn't show how the service behaves with millions of rows.
- The capacity numbers are one 30 second run per rate, so a single stall can change a row a lot, and 30 seconds is short to see if a queue drains.
- Only `POST /transfers` between customer accounts. Deposits all lock the same funding row (one per currency), so they would behave very differently. I left them out on purpose.

## How to run it

```
APP_JAVA_HOME=$(ls -d ~/.gradle/jdks/*21*/jdk-*/Contents/Home) scripts/load-test.sh steady 1000 120 3 250
```

The script refuses to start unless `APP_JAVA_HOME` is a JDK 21, the version the service ships with. The arguments are a name, the number of accounts, seconds per run, repeats and one or more rates. Results, logs and the Gatling reports go to `build/load-test/`. At the end the script prints the table rows and the analysis of the slow seconds.

The runs above, on the current code:
```
scripts/load-test.sh capacity 1000 30 1 50 100 200 300 400 500 600
scripts/load-test.sh capacity-high 1000 30 1 700 800 1000
scripts/load-test.sh steady 1000 120 3 250
scripts/load-test.sh contention 10 120 3 250
```

The before and after comparison, run once on each version of the code:
```
scripts/load-test.sh diag 1000 120 2 400     (before the change)
scripts/load-test.sh after 1000 120 2 400    (after the change)
```
