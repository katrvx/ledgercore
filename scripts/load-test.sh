#!/usr/bin/env bash
# load test for POST /transfers, results go to build/load-test/
#
# usage: scripts/load-test.sh NAME ACCOUNTS SECONDS REPEATS RATE [RATE...]
#   scripts/load-test.sh capacity 1000 30 1 50 100 200 300 400
#   scripts/load-test.sh steady 1000 120 3 200
#   scripts/load-test.sh contention 10 120 3 200
#
# APP_JAVA_HOME must be a JDK 21, the version the service ships with. the one gradle downloaded works:
#   APP_JAVA_HOME=$(ls -d ~/.gradle/jdks/*21*/jdk-*/Contents/Home) scripts/load-test.sh ...
#
# every measured run gets its own warm-up first (WARMUP_SECONDS at WARMUP_RATE), which is not reported.
# it starts its own postgres and redis as the compose project "ledgercore-load" on other ports,
# so it never touches the dev database, and removes them at the end.
set -euo pipefail

if [ "$#" -lt 5 ]; then
  sed -n '4,10p' "$0"
  exit 1
fi

# the numbers only describe what we ship if the app runs on java 21
APP_JAVA_HOME="${APP_JAVA_HOME:-${JAVA_HOME:-}}"
APP_JAVA_VERSION="$("$APP_JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/.*java.specification.version = //p' || true)"
if [ "$APP_JAVA_VERSION" != "21" ]; then
  echo "APP_JAVA_HOME must be a JDK 21, got '${APP_JAVA_VERSION:-nothing}' from '$APP_JAVA_HOME'"
  exit 1
fi

NAME="$1"
ACCOUNTS="$2"
SECONDS_PER_RUN="$3"
REPEATS="$4"
shift 4
RATES=("$@")

WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
WARMUP_RATE="${WARMUP_RATE:-20}"
APP_PORT=8090
APP_HEAP="-Xms512m -Xmx512m"

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build/load-test"
RESULTS="$OUT/$NAME.tsv"
RUNS="$OUT/$NAME-runs.tsv"
APP_LOG="$OUT/$NAME-app.log"
RESOURCES="$OUT/$NAME-resources.tsv"
PG_ACTIVITY="$OUT/$NAME-pg-activity.tsv"
REDIS_PING="$OUT/$NAME-redis-ping.tsv"
# only for explaining latency: gc pauses to a file, and hikari pool stats every 2 s instead of every 30 s
APP_DIAGNOSTICS="-Xlog:gc:file=$OUT/$NAME-gc.log:time,uptime -Dcom.zaxxer.hikari.housekeeping.periodMs=2000 -Dlogback.configurationFile=$REPO/scripts/load-test-logback.xml"
COMPOSE=(docker compose -p ledgercore-load)
export POSTGRES_PORT=55432
export REDIS_PORT=56379

cd "$REPO"
mkdir -p "$OUT"
printf "name\trun\ttarget_rps\tachieved_rps\trequests\tfailed\tp50_ms\tp95_ms\tp99_ms\tmax_ms\n" > "$RESULTS"
printf "rate\trun\tstart_utc\tend_utc\n" > "$RUNS"

stop_all() {
  for pid in ${SAMPLER_PID:-} ${PG_SAMPLER_PID:-} ${REDIS_SAMPLER_PID:-}; do
    kill "$pid" 2> /dev/null || true
    wait "$pid" 2> /dev/null || true
  done
  if [ -n "${APP_PID:-}" ]; then
    kill "$APP_PID" 2> /dev/null || true
    wait "$APP_PID" 2> /dev/null || true
  fi
  "${COMPOSE[@]}" down -v > /dev/null 2>&1 || true
}
trap stop_all EXIT

echo "== environment" | tee "$OUT/$NAME-environment.txt"
{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "cpu: $(sysctl -n machdep.cpu.brand_string 2> /dev/null || grep -m1 'model name' /proc/cpuinfo)"
  echo "cores: $(getconf _NPROCESSORS_ONLN)"
  echo "memory bytes: $(sysctl -n hw.memsize 2> /dev/null || grep MemTotal /proc/meminfo)"
  echo "os: $(uname -srm)"
  echo "docker: $(docker info --format 'cpus={{.NCPU}} memory={{.MemTotal}} version={{.ServerVersion}}')"
  echo "app jvm: $("$APP_JAVA_HOME/bin/java" -version 2>&1 | sed -n 2p)"
  echo "app heap: $APP_HEAP"
  echo "app diagnostics: $APP_DIAGNOSTICS"
  echo "gatling jvm: gradle plugin defaults (-Xmx1G)"
} | tee -a "$OUT/$NAME-environment.txt"

echo "== starting postgres and redis"
"${COMPOSE[@]}" down -v > /dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --wait > /dev/null 2>&1
echo "postgres: $("${COMPOSE[@]}" exec -T postgres psql -U ledgercore -d ledgercore -At -c 'show server_version')" \
  | tee -a "$OUT/$NAME-environment.txt"
echo "redis: $("${COMPOSE[@]}" exec -T redis redis-server --version | cut -d' ' -f3)" | tee -a "$OUT/$NAME-environment.txt"
# log every autovacuum and autoanalyze with its timing, only in this throwaway database and only to explain spikes
"${COMPOSE[@]}" exec -T postgres psql -U ledgercore -d ledgercore -q -c "alter system set log_autovacuum_min_duration = 0"
"${COMPOSE[@]}" exec -T postgres psql -U ledgercore -d ledgercore -q -At -c "select pg_reload_conf()" > /dev/null
echo "postgres log_autovacuum_min_duration: $("${COMPOSE[@]}" exec -T postgres psql -U ledgercore -d ledgercore -At -c 'show log_autovacuum_min_duration')" \
  | tee -a "$OUT/$NAME-environment.txt"

echo "== starting the app"
./gradlew -q installDist
# fraud rules still run on every transfer, but with limits no load test traffic can reach
PORT=$APP_PORT \
  DATABASE_URL="jdbc:postgresql://localhost:$POSTGRES_PORT/ledgercore" DATABASE_USER=ledgercore DATABASE_PASSWORD=ledgercore \
  REDIS_URL="redis://localhost:$REDIS_PORT" \
  FRAUD_VELOCITY_MAX_TRANSFERS=2000000000 FRAUD_ABSOLUTE_LIMIT=9000000000000000000 \
  FRAUD_ANOMALY_MULTIPLIER=9000000000000000000 FRAUD_NEW_RECIPIENT_LIMIT=9000000000000000000 \
  JAVA_HOME="$APP_JAVA_HOME" JAVA_OPTS="$APP_HEAP $APP_DIAGNOSTICS" \
  build/install/ledgercore/bin/ledgercore > "$APP_LOG" 2>&1 &
# the start script ends with exec java, so this is the app's own pid
APP_PID=$!
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:$APP_PORT/ready" > /dev/null; then
    break
  fi
  sleep 1
done
curl -sf "http://localhost:$APP_PORT/ready" > /dev/null

# cpu every 2 s: docker stats is % of one core inside the docker vm, ps is a short moving average
sample_resources() {
  printf "time_utc\tapp_cpu\tgatling_cpu\tpostgres_cpu\tredis_cpu\n"
  while true; do
    local now app gatling_cpu docker_cpu
    now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    # "|| true" everywhere: between runs there is no gatling process, and that must not stop the sampler
    app="$(ps -o %cpu= -p "$APP_PID" | tr -d ' ' || true)"
    gatling_cpu="$( (ps -axo %cpu=,command= | grep -F 'io.gatling' | grep -vF grep || true) | awk '{s += $1} END {print s + 0}')"
    docker_cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' ledgercore-load-postgres-1 ledgercore-load-redis-1 | tr -d '%' | paste -sd '\t' - || true)"
    printf "%s\t%s\t%s\t%s\n" "$now" "$app" "$gatling_cpu" "$docker_cpu"
    sleep 2
  done
}
sample_resources > "$RESOURCES" 2> /dev/null &
SAMPLER_PID=$!

# what the app's connections are doing every 0.5 s, from one psql session:
# "idle in transaction" means postgres waits for the app, "active" with an IO wait means it waits for the disk
"${COMPOSE[@]}" exec -T postgres psql -U ledgercore -d ledgercore -At -F $'\t' > "$PG_ACTIVITY" 2> /dev/null <<'SQL' &
select to_char(clock_timestamp() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
       state,
       coalesce(wait_event_type, '-') || '/' || coalesce(wait_event, '-'),
       count(*),
       round(max(extract(epoch from clock_timestamp() - state_change)) * 1000)
from pg_stat_activity
where backend_type = 'client backend' and datname = 'ledgercore' and pid <> pg_backend_pid()
group by state, wait_event_type, wait_event
\watch 0.5
SQL
PG_SAMPLER_PID=$!

# redis round trips from the host, through the same docker port forward the app uses, 20 per second
python3 - "$REDIS_PORT" > "$REDIS_PING" 2> /dev/null <<'EOF' &
import socket, sys, time
from datetime import datetime, timezone
connection = socket.create_connection(("localhost", int(sys.argv[1])))
print("time_utc\tping_ms", flush=True)
while True:
    started = time.perf_counter()
    connection.sendall(b"PING\r\n")
    connection.recv(64)
    millis = (time.perf_counter() - started) * 1000
    now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    print(f"{now}\t{millis:.2f}", flush=True)
    time.sleep(0.05)
EOF
REDIS_SAMPLER_PID=$!

gatling() {
  local accounts="$1" rate="$2" seconds="$3" description="$4"
  ./gradlew -q gatlingRun --non-interactive --simulation com.ledgercore.load.TransferSimulation \
    --run-description "$description" \
    -PbaseUrl="http://localhost:$APP_PORT" -Paccounts="$accounts" -Prate="$rate" -Pseconds="$seconds"
}

latest_report() {
  ls -td build/reports/gatling/transfersimulation-* | head -1
}

# money can't appear or disappear, whatever the load was
check_invariants() {
  "${COMPOSE[@]}" exec -T postgres psql -U ledgercore -d ledgercore -At -F ' ' -c "
    select
      'ledger_sum=' || coalesce((select sum(amount) from ledger_entries), 0),
      'balance_sum=' || (select sum(balance) from accounts),
      'entries_minus_2x_completed=' || ((select count(*) from ledger_entries) - 2 * (select count(*) from transfers where status = 'COMPLETED')),
      'negative_customers=' || (select count(*) from accounts where type = 'CUSTOMER' and balance < 0),
      'balance_not_sum_of_entries=' || (select count(*) from accounts a
          where a.balance <> coalesce((select sum(e.amount) from ledger_entries e where e.account_id = a.id), 0))"
}

for rate in "${RATES[@]}"; do
  for run in $(seq 1 "$REPEATS"); do
    echo "== $NAME: warm-up ${WARMUP_SECONDS}s at ${WARMUP_RATE}/s"
    gatling 100 "$WARMUP_RATE" "$WARMUP_SECONDS" "warm-up" > "$OUT/$NAME-warmup-$rate-$run.log" 2>&1

    echo "== $NAME: run $run, ${rate}/s for ${SECONDS_PER_RUN}s on $ACCOUNTS accounts"
    rc=0
    started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    gatling "$ACCOUNTS" "$rate" "$SECONDS_PER_RUN" "$NAME run $run at $rate/s" > "$OUT/$NAME-$rate-$run.log" 2>&1 || rc=$?
    printf "%s\t%s\t%s\t%s\n" "$rate" "$run" "$started" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$RUNS"
    report="$(latest_report)"
    cp -R "$report" "$OUT/$NAME-$rate-$run-report"
    # gatling 3.15 has no json stats file any more, so this reads the summary it prints:
    # "> request count   |  total |  ok |  ko" with "-" for zero
    python3 - "$OUT/$NAME-$rate-$run.log" "$NAME" "$run" "$rate" >> "$RESULTS" <<'EOF'
import re, sys
path, name, run, rate = sys.argv[1:5]
total, ko = {}, {}
for line in open(path):
    m = re.match(r"> (.+?)\s*\|\s*(\S+)\s*\|\s*(\S+)\s*\|\s*(\S+)", line)
    if m:
        total[m.group(1)] = m.group(2).replace("-", "0")
        ko[m.group(1)] = m.group(4).replace("-", "0")
row = [name, run, rate,
       total["mean throughput (rps)"],
       total["request count"],
       ko["request count"],
       total["response time 50th percentile (ms)"],
       total["response time 95th percentile (ms)"],
       total["response time 99th percentile (ms)"],
       total["max response time (ms)"]]
print("\t".join(row))
EOF
    tail -1 "$RESULTS"
    echo "gatling exit=$rc, invariants: $(check_invariants)" | tee -a "$OUT/$NAME-invariants.txt"
  done
done

echo "== app errors and warnings during the test"
grep -E '"severity":"(WARNING|ERROR)"' "$APP_LOG" | cut -c1-200 | sort | uniq -c | head -20 || echo "none"
echo "== results in $RESULTS"
cat "$RESULTS"

# postgres 18 logs checkpoints by default, they are one suspect for latency spikes
"${COMPOSE[@]}" logs --no-color postgres > "$OUT/$NAME-postgres.log" 2>&1
echo "== what happened during the slow seconds"
python3 "$REPO/scripts/analyze-load-test.py" "$OUT" "$NAME"
