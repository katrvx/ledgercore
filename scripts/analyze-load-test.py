#!/usr/bin/env python3
# matches the slow seconds of each load test run with gc pauses, pool waits, postgres checkpoints and cpu
# usage: scripts/analyze-load-test.py build/load-test NAME   (scripts/load-test.sh runs it at the end)
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone

SLOW_P99_MS = 100
GC_PAUSE_MS = 20

out, name = sys.argv[1], sys.argv[2]


def utc(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def p(values, share):
    values = sorted(values)
    return values[max(0, int(round(share * len(values))) - 1)]


# the app log: one json object per line, other lines (jvm warnings) are skipped
# hikari writes "Pool stats" every 2 s (a fair sample) and "Connection not added" each time a thread
# has to wait while the pool is full (an event, far more often during waits, so never used as a sample)
requests, pool, pool_waits = [], [], []
for line in open(f"{out}/{name}-app.log"):
    if not line.startswith("{"):
        continue
    entry = json.loads(line)
    message = entry["message"]
    if entry["logger"] == "access" and message.startswith("POST /transfers "):
        requests.append((utc(entry["time"]), int(message.split()[-1][:-2])))
    if entry["logger"] != "com.zaxxer.hikari.pool.HikariPool":
        continue
    stats = re.search(r"active=(\d+), waiting=(\d+)", message)
    if "Pool stats" in message and stats:
        pool.append((utc(entry["time"]), int(stats.group(1)), int(stats.group(2))))
    if "Connection not added" in message:
        pool_waits.append(utc(entry["time"]))

# [2026-09-26T16:50:01.123+0100][12.345s] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 50M->10M(512M) 3.210ms
pauses = []
for line in open(f"{out}/{name}-gc.log"):
    m = re.match(r"\[([^\]]+)\].* Pause .* ([\d.]+)ms$", line.strip())
    if m:
        pauses.append((datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z"), float(m.group(2))))

# checkpoint starting ... checkpoint complete, from the postgres container log
checkpoints, started = [], None
for line in open(f"{out}/{name}-postgres.log"):
    m = re.search(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) UTC .*checkpoint (starting|complete)", line)
    if m:
        at = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=timezone.utc)
        if m.group(2) == "starting":
            started = at
        elif started is not None:
            checkpoints.append((started, at))
            started = None
# a spread checkpoint takes minutes, so one can still be running when the log is saved
if started is not None:
    checkpoints.append((started, datetime.max.replace(tzinfo=timezone.utc)))

# "automatic vacuum of table ..." is logged when it ends, a later line of the same entry says "elapsed: 0.03 s"
autovacuums, current = [], None
for line in open(f"{out}/{name}-postgres.log"):
    m = re.search(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) UTC .*automatic (vacuum|analyze) of table \"[^\"]*\.(\w+)\"", line)
    if m:
        ended = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=timezone.utc)
        current = (ended, m.group(2), m.group(3))
        continue
    elapsed = re.search(r"elapsed: ([\d.]+) s", line)
    if elapsed and current:
        ended, kind, table = current
        autovacuums.append((ended - timedelta(seconds=float(elapsed.group(1))), ended, f"{kind} {table}"))
        current = None

resources = []
lines = open(f"{out}/{name}-resources.tsv").read().splitlines()[1:]
for line in lines:
    cells = line.split("\t")
    if len(cells) == 5 and all(cells[1:]):
        resources.append((utc(cells[0]), *[float(c) for c in cells[1:]]))


# pg_stat_activity every 0.5 s: time, state, wait_event_type/wait_event, connections, longest in that state (ms)
activity = {}
if os.path.exists(f"{out}/{name}-pg-activity.tsv"):
    for line in open(f"{out}/{name}-pg-activity.tsv"):
        cells = line.rstrip("\n").split("\t")
        if len(cells) == 5:
            state = cells[1] if cells[1] != "active" or cells[2] != "-/-" else "active on cpu"
            if cells[1] == "active" and cells[2] != "-/-":
                state = "active waiting " + cells[2]
            activity.setdefault(utc(cells[0]), []).append((state, int(cells[3]), int(cells[4] or 0)))

redis_pings = []
if os.path.exists(f"{out}/{name}-redis-ping.tsv"):
    for line in open(f"{out}/{name}-redis-ping.tsv").read().splitlines()[1:]:
        cells = line.split("\t")
        if len(cells) == 2:
            redis_pings.append((utc(cells[0]), float(cells[1])))


# mean number of connections per state over the samples taken in this second
def activity_in(second):
    samples = [rows for at, rows in activity.items() if second <= at < second + timedelta(seconds=1)]
    counts = {}
    for rows in samples:
        for state, connections, _ in rows:
            counts[state] = counts.get(state, 0) + connections / len(samples)
    return counts


def seconds_facts(second):
    near = [c for c in resources if abs((c[0] - second).total_seconds()) <= 2]
    return {
        "activity": activity_in(second),
        "redis_ping_ms": max([ms for at, ms in redis_pings if second <= at < second + timedelta(seconds=1)], default=None),
        "gc_ms": sum(ms for at, ms in pauses if second - timedelta(seconds=1) <= at < second + timedelta(seconds=1)),
        "waiting": max([w for at, a, w in pool if abs((at - second).total_seconds()) <= 2], default=None),
        "wait_events": sum(1 for at in pool_waits if second <= at < second + timedelta(seconds=1)),
        "checkpoint": any(start <= second + timedelta(seconds=1) and second <= end for start, end in checkpoints),
        # a vacuum in the second before also counts, the queue it causes takes a moment to drain
        "autovacuum": sorted({what for start, end, what in autovacuums
                              if start <= second + timedelta(seconds=1) and second - timedelta(seconds=1) <= end}),
        "postgres_cpu": max([c[3] for c in near], default=None),
        "app_cpu": max([c[1] for c in near], default=None),
    }


def share(rows, test):
    if not rows:
        return "-"
    return "%d%%" % round(100 * sum(1 for r in rows if test(r)) / len(rows))


def mean(rows, key):
    values = [r[key] for r in rows if r[key] is not None]
    return "-" if not values else "%.0f" % (sum(values) / len(values))


for line in open(f"{out}/{name}-runs.tsv").read().splitlines()[1:]:
    rate, run, start, end = line.split("\t")
    # the shell only records whole seconds, so the window moves by one second at both ends:
    # the warm-up can end inside the start second, and gradle startup delays the first measured request by more
    start, end = utc(start) + timedelta(seconds=1), utc(end) + timedelta(seconds=1)
    in_run = [(at, ms) for at, ms in requests if start <= at <= end]
    if not in_run:
        continue
    by_second = {}
    for at, ms in in_run:
        by_second.setdefault(at.replace(microsecond=0), []).append(ms)
    seconds = []
    for second, values in sorted(by_second.items()):
        facts = seconds_facts(second)
        facts.update(second=second, p99=p(values, 0.99), count=len(values))
        seconds.append(facts)
    slow = [s for s in seconds if s["p99"] > SLOW_P99_MS]
    normal = [s for s in seconds if s["p99"] <= SLOW_P99_MS]
    run_pauses = [ms for at, ms in pauses if start <= at <= end]
    run_pool = [w for at, a, w in pool if start <= at <= end]
    run_checkpoints = [c for c in checkpoints if c[0] <= end and start <= c[1]]
    run_autovacuums = [a for a in autovacuums if a[0] <= end and start <= a[1]]

    print(f"--- {name} {rate}/s run {run}: {len(in_run)} transfers in {len(seconds)} seconds")
    print(f"server side: p50={p([ms for _, ms in in_run], 0.5)} ms  p99={p([ms for _, ms in in_run], 0.99)} ms  "
          f"max={max(ms for _, ms in in_run)} ms")
    print(f"gc: {len(run_pauses)} pauses, total {sum(run_pauses):.0f} ms, longest {max(run_pauses, default=0):.1f} ms")
    print(f"pool: {len(run_pool)} samples every 2 s, waiting > 0 in {share(run_pool, lambda w: w > 0)}, "
          f"most waiting {max(run_pool, default=0)}")
    print(f"postgres checkpoints overlapping the run: {len(run_checkpoints)}")
    print(f"autovacuum and autoanalyze runs: {len(run_autovacuums)}, "
          f"longest {max([(b - a).total_seconds() for a, b, _ in run_autovacuums], default=0):.2f} s")
    print(f"slow seconds (server p99 > {SLOW_P99_MS} ms): {len(slow)} of {len(seconds)}")
    print(f"{'':30} {'slow seconds':>14} {'normal seconds':>16}")
    print(f"{'gc pause >= %d ms nearby' % GC_PAUSE_MS:30} {share(slow, lambda s: s['gc_ms'] >= GC_PAUSE_MS):>14} "
          f"{share(normal, lambda s: s['gc_ms'] >= GC_PAUSE_MS):>16}")
    print(f"{'a thread waited for the pool':30} {share(slow, lambda s: s['wait_events'] > 0):>14} "
          f"{share(normal, lambda s: s['wait_events'] > 0):>16}")
    print(f"{'during a checkpoint':30} {share(slow, lambda s: s['checkpoint']):>14} "
          f"{share(normal, lambda s: s['checkpoint']):>16}")
    print(f"{'autovacuum within 1 s':30} {share(slow, lambda s: s['autovacuum']):>14} "
          f"{share(normal, lambda s: s['autovacuum']):>16}")
    print(f"{'postgres cpu % (mean of max)':30} {mean(slow, 'postgres_cpu'):>14} {mean(normal, 'postgres_cpu'):>16}")
    print(f"{'app cpu % (mean of max)':30} {mean(slow, 'app_cpu'):>14} {mean(normal, 'app_cpu'):>16}")
    if redis_pings:
        print(f"{'redis ping ms (mean of max)':30} {mean(slow, 'redis_ping_ms'):>14} {mean(normal, 'redis_ping_ms'):>16}")
    if activity:
        print("postgres connections by state (mean per second):")
        states = sorted({state for s in seconds for state in s["activity"]})
        for state in states:
            slow_mean = sum(s["activity"].get(state, 0) for s in slow) / len(slow) if slow else None
            normal_mean = sum(s["activity"].get(state, 0) for s in normal) / len(normal) if normal else None
            print(f"  {state:36} {'-' if slow_mean is None else '%.1f' % slow_mean:>8} "
                  f"{'-' if normal_mean is None else '%.1f' % normal_mean:>16}")
    for s in sorted(slow, key=lambda s: -s["p99"])[:5]:
        print(f"  slowest {s['second'].strftime('%H:%M:%S')}Z p99={s['p99']} ms requests={s['count']} "
              f"gc={s['gc_ms']:.0f} ms pool_wait_events={s['wait_events']} checkpoint={s['checkpoint']} "
              f"autovacuum={','.join(s['autovacuum']) or '-'} "
              f"postgres_cpu={s['postgres_cpu']} app_cpu={s['app_cpu']} redis_ping_ms={s['redis_ping_ms']} "
              f"postgres={ {k: round(v, 1) for k, v in s['activity'].items()} }")
    print()
