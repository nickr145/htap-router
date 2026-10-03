#!/bin/bash
# Benchmarks syncFromPostgres(): cold sync of 50k new rows vs. a repeat sync with no new rows.
# Usage: bench_sync.sh <worktree-dir> <label>
#   <worktree-dir> can be this repo (current code) or a `git worktree` checkout of an older
#   commit, to compare behavior across history — see bench/README.md for the exact commits used.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${BENCH_OUT_DIR:-$SCRIPT_DIR/out}"
mkdir -p "$OUT_DIR"
DIR="$1"
LABEL="$2"
LOG="$OUT_DIR/${LABEL}-app.log"
PORT=8089
export SERVER_PORT=$PORT

echo "=== [$LABEL] building & starting app from $DIR (port $PORT) ==="
cd "$DIR"
rm -f "$LOG"
(SERVER_PORT=$PORT ./gradlew bootRun -q > "$LOG" 2>&1 &)

echo "=== [$LABEL] waiting for app on :$PORT ==="
for i in $(seq 1 60); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/api/analytics/summary || true)
  if [[ "$CODE" == "200" ]]; then
    echo "[$LABEL] app responding (HTTP $CODE) after ${i}s"
    break
  fi
  sleep 1
done
if [[ "$CODE" != "200" ]]; then
  echo "[$LABEL] FAILED to come up, last code=$CODE"
  tail -40 "$LOG"
  exit 1
fi

CID=$(docker ps --filter "publish=5432/tcp" -q | head -1)
if [[ -z "$CID" ]]; then
  CID=$(docker ps --format '{{.ID}} {{.Image}}' | grep postgres | awk '{print $1}' | head -1)
fi
echo "[$LABEL] postgres container: $CID"

echo "=== [$LABEL] seeding 50k transactions ==="
docker exec -i "$CID" psql -U myuser -d mydatabase < "$SCRIPT_DIR/seed.sql" > "$OUT_DIR/${LABEL}-seed.log" 2>&1
echo "[$LABEL] seed done"

echo "=== [$LABEL] sync call 1 (cold, 50k rows) ==="
START=$(date +%s%N)
RESP1=$(curl -s -X POST http://localhost:$PORT/api/analytics/sync)
END=$(date +%s%N)
MS1=$(( (END - START) / 1000000 ))
echo "[$LABEL] sync1 resp: $RESP1"
echo "[$LABEL] sync1 time: ${MS1}ms"

echo "=== [$LABEL] sync call 2 (repeat, no new rows) ==="
START=$(date +%s%N)
RESP2=$(curl -s -X POST http://localhost:$PORT/api/analytics/sync)
END=$(date +%s%N)
MS2=$(( (END - START) / 1000000 ))
echo "[$LABEL] sync2 resp: $RESP2"
echo "[$LABEL] sync2 time: ${MS2}ms"

echo "RESULT $LABEL sync1_ms=$MS1 sync2_ms=$MS2" >> "$OUT_DIR/results.txt"

echo "=== [$LABEL] stopping app ==="
pkill -f "HtapRouterApplication" 2>/dev/null || true
sleep 2
if [[ -n "$CID" ]]; then
  docker stop "$CID" >/dev/null 2>&1 || true
  docker rm "$CID" >/dev/null 2>&1 || true
fi
sleep 1
echo "=== [$LABEL] done ==="
