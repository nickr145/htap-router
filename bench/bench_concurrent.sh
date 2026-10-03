#!/bin/bash
# Benchmarks concurrent /api/analytics/summary reads fired while a sync is in flight:
# correctness (does any read see a count below the pre-sync baseline?) and read latency.
# Usage: bench_concurrent.sh <worktree-dir> <label>
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${BENCH_OUT_DIR:-$SCRIPT_DIR/out}"
mkdir -p "$OUT_DIR"
DIR="$1"
LABEL="$2"
LOG="$OUT_DIR/${LABEL}-conc-app.log"
PORT=8089

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

echo "=== [$LABEL] seeding baseline 50k + initial sync ==="
docker exec -i "$CID" psql -U myuser -d mydatabase < "$SCRIPT_DIR/seed.sql" > "$OUT_DIR/${LABEL}-conc-seed1.log" 2>&1
curl -s -X POST http://localhost:$PORT/api/analytics/sync > "$OUT_DIR/${LABEL}-conc-sync1.json"
echo "[$LABEL] baseline sync: $(cat "$OUT_DIR/${LABEL}-conc-sync1.json")"
BASELINE=$(curl -s http://localhost:$PORT/api/analytics/summary | grep -o '"totalTransactionCount":[0-9]*' | grep -o '[0-9]*$')
echo "[$LABEL] baseline count: $BASELINE"

echo "=== [$LABEL] seeding second 50k batch (to sync concurrently with reads) ==="
docker exec -i "$CID" psql -U myuser -d mydatabase < "$SCRIPT_DIR/seed.sql" > "$OUT_DIR/${LABEL}-conc-seed2.log" 2>&1

READS_LOG="$OUT_DIR/${LABEL}-reads.log"
> "$READS_LOG"

echo "=== [$LABEL] firing sync in background + polling reads concurrently ==="
SYNC_START=$(date +%s%N)
curl -s -X POST http://localhost:$PORT/api/analytics/sync > "$OUT_DIR/${LABEL}-conc-sync2.json" &
SYNC_PID=$!

while kill -0 $SYNC_PID 2>/dev/null; do
  T0=$(date +%s%N)
  RESP=$(curl -s http://localhost:$PORT/api/analytics/summary)
  T1=$(date +%s%N)
  LAT_MS=$(( (T1 - T0) / 1000000 ))
  COUNT=$(echo "$RESP" | grep -o '"totalTransactionCount":[0-9]*' | grep -o '[0-9]*$')
  echo "${T0} ${LAT_MS} ${COUNT}" >> "$READS_LOG"
done
wait $SYNC_PID
SYNC_END=$(date +%s%N)
SYNC_MS=$(( (SYNC_END - SYNC_START) / 1000000 ))
echo "[$LABEL] sync2 (concurrent) resp: $(cat "$OUT_DIR/${LABEL}-conc-sync2.json")"
echo "[$LABEL] sync2 wall time: ${SYNC_MS}ms"

NUM_READS=$(wc -l < "$READS_LOG" | tr -d ' ')
MIN_COUNT=$(awk '{print $3}' "$READS_LOG" | sort -n | head -1)
MAX_LAT=$(awk '{print $2}' "$READS_LOG" | sort -n | tail -1)
AVG_LAT=$(awk '{sum+=$2; n++} END {if (n>0) print int(sum/n); else print 0}' "$READS_LOG")
P99_LAT=$(awk '{print $2}' "$READS_LOG" | sort -n | awk '{a[NR]=$1} END {idx=int(NR*0.99); if(idx<1)idx=1; print a[idx]}')

echo "[$LABEL] reads during sync: n=$NUM_READS min_count=$MIN_COUNT baseline=$BASELINE avg_lat_ms=$AVG_LAT p99_lat_ms=$P99_LAT max_lat_ms=$MAX_LAT"
echo "RESULT_CONCURRENT $LABEL sync_ms=$SYNC_MS reads=$NUM_READS baseline=$BASELINE min_count=$MIN_COUNT avg_lat_ms=$AVG_LAT p99_lat_ms=$P99_LAT max_lat_ms=$MAX_LAT" >> "$OUT_DIR/results.txt"

echo "=== [$LABEL] stopping app ==="
pkill -f "HtapRouterApplication" 2>/dev/null || true
sleep 2
if [[ -n "$CID" ]]; then
  docker stop "$CID" >/dev/null 2>&1 || true
  docker rm "$CID" >/dev/null 2>&1 || true
fi
sleep 1
echo "=== [$LABEL] done ==="
