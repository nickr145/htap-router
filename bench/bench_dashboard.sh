#!/bin/bash
# Benchmarks getDashboardData() latency: run against this repo as-is for the parallel
# (StructuredTaskScope) version, or with bench/sequential-dashboard.patch applied for the
# sequential comparison — see bench/README.md.
# Usage: bench_dashboard.sh <worktree-dir> <label> [n-requests]
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${BENCH_OUT_DIR:-$SCRIPT_DIR/out}"
mkdir -p "$OUT_DIR"
DIR="$1"
LABEL="$2"
N="${3:-30}"
LOG="$OUT_DIR/${LABEL}-dash-app.log"
PORT=8089
ACCT="00000000-0000-0000-0000-000000000001"

echo "=== [$LABEL] building & starting app from $DIR (port $PORT) ==="
cd "$DIR"
rm -f "$LOG"
(SERVER_PORT=$PORT ./gradlew bootRun -q > "$LOG" 2>&1 &)

echo "=== [$LABEL] waiting for app on :$PORT ==="
CODE=""
for i in $(seq 1 60); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/api/analytics/summary || true)
  if [[ "$CODE" == "200" ]]; then
    echo "[$LABEL] app responding after ${i}s"
    break
  fi
  sleep 1
done
if [[ "$CODE" != "200" ]]; then
  echo "[$LABEL] FAILED to come up"; tail -40 "$LOG"; exit 1
fi

CID=$(docker ps --format '{{.ID}} {{.Image}}' | grep postgres | awk '{print $1}' | head -1)
echo "[$LABEL] postgres container: $CID"

echo "=== [$LABEL] seeding account + 50k transactions ==="
docker exec -i "$CID" psql -U myuser -d mydatabase < "$SCRIPT_DIR/seed.sql" > "$OUT_DIR/${LABEL}-dash-seed.log" 2>&1

echo "=== [$LABEL] running $N sequential dashboard requests ==="
LAT_LOG="$OUT_DIR/${LABEL}-dash-lat.log"
> "$LAT_LOG"
# warm-up (JIT, connection pool, OS page cache) — not counted
for i in 1 2 3; do curl -s "http://localhost:$PORT/api/accounts/$ACCT/dashboard" > /dev/null; done

for i in $(seq 1 "$N"); do
  T0=$(date +%s%N)
  curl -s "http://localhost:$PORT/api/accounts/$ACCT/dashboard" > /dev/null
  T1=$(date +%s%N)
  echo $(( (T1 - T0) / 1000000 )) >> "$LAT_LOG"
done

P50=$(sort -n "$LAT_LOG" | awk '{a[NR]=$1} END {print a[int(NR*0.50)+1]}')
P99=$(sort -n "$LAT_LOG" | awk '{a[NR]=$1} END {print a[int(NR*0.99)]}')
AVG=$(awk '{sum+=$1; n++} END {print int(sum/n)}' "$LAT_LOG")
echo "[$LABEL] dashboard latency over $N calls: avg=${AVG}ms p50=${P50}ms p99=${P99}ms"
echo "RESULT_DASHBOARD $LABEL n=$N avg_ms=$AVG p50_ms=$P50 p99_ms=$P99" >> "$OUT_DIR/results.txt"

echo "=== [$LABEL] stopping app ==="
pkill -f "HtapRouterApplication" 2>/dev/null || true
sleep 2
if [[ -n "$CID" ]]; then
  docker stop "$CID" >/dev/null 2>&1 || true
  docker rm "$CID" >/dev/null 2>&1 || true
fi
sleep 1
echo "=== [$LABEL] done ==="
