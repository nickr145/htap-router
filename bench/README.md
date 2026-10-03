# Benchmarks

Ad hoc scripts used to produce the numbers in the main [README's Benchmarks section](../README.md#benchmarks). Not run in CI — these spin up real Docker Postgres + the app on port `8089` and take a few minutes each.

All scripts seed data via direct SQL (`seed.sql`, 50k rows via `generate_series` against one benchmark account), hit the real HTTP API, and log results to `bench/out/` (gitignored) plus an append-only `bench/out/results.txt`.

## What's compared

Three points in this repo's history, run against the same scripts, same seed data, same machine:

| Label | Commit | What it is |
|---|---|---|
| `OLD` | [`10be91e`](https://github.com/nickr145/htap-router/commit/10be91e) | Original: full table `DELETE` + reinsert every sync call, unsynchronized reads |
| `MID` | [`7df7905`](https://github.com/nickr145/htap-router/commit/7df7905) | First fix: incremental `created_at` watermark, `synchronized` on both read and write |
| current | HEAD | Current: `seq`-column watermark, `DuckDBAppender`, per-operation duplicated DuckDB connections (MVCC) |

To reproduce the historical comparisons, check out the old commit into a separate worktree so it doesn't disturb your working tree, then run the scripts against that path:

```bash
git worktree add /tmp/htap-bench-old 10be91e
bench/bench_sync.sh /tmp/htap-bench-old OLD
git worktree remove /tmp/htap-bench-old
```

(The `10be91e` commit's watermark isn't involved — only current HEAD and `7df7905` use one. `7df7905` additionally needs its `OffsetDateTime.MIN` initial watermark value bumped to a real date, e.g. `OffsetDateTime.of(1970,1,1,0,0,0,0,ZoneOffset.UTC)`, in that worktree only — `OffsetDateTime.MIN` overflows Postgres' `timestamptz` range and 500s on the very first sync. This is a real, harmless-in-practice bug in that intermediate commit, fixed implicitly by the later switch to a `long` sequence watermark.)

## Scripts

### `bench_sync.sh <worktree-dir> <label>`
Seeds 50k transactions, times a cold `POST /api/analytics/sync` (all 50k new), then times an immediate repeat sync (0 new rows). Shows the cost of a full resync vs. an incremental one.

```bash
./bench/bench_sync.sh . CURRENT
```

### `bench_concurrent.sh <worktree-dir> <label>`
Seeds and syncs a 50k baseline, seeds another 50k, then fires a sync in the background while polling `GET /api/analytics/summary` in a tight loop for the sync's duration. Reports how many reads completed, the minimum transaction count any read observed (should never be below the pre-sync baseline — a lower value means a read caught the table mid-reload), and read latency (avg/p99/max).

```bash
./bench/bench_concurrent.sh . CURRENT
```

### `bench_dashboard.sh <worktree-dir> <label> [n]`
Seeds a benchmark account with 50k transactions, then fires `n` (default 30) sequential `GET /api/accounts/{id}/dashboard` requests, reporting avg/p50/p99 latency.

Run once as-is (parallel, `StructuredTaskScope`), then apply `sequential-dashboard.patch` to swap in a sequential version of `DashboardService.getDashboardData()` for comparison, then revert:

```bash
./bench/bench_dashboard.sh . PARALLEL
git apply bench/sequential-dashboard.patch
./bench/bench_dashboard.sh . SEQUENTIAL
git apply -R bench/sequential-dashboard.patch   # revert — do not commit the patched file
```

## Gotchas

- All scripts assume port `8089` is free (chosen to dodge port `8080`, which may already be in use by something else on your machine) and that `docker` is running.
- Each script starts its own Postgres via the project's `compose.yaml` / Spring Boot Docker Compose support and tears it down at the end. If a run is interrupted, you may need to `docker ps` and manually stop/remove a leftover `*-postgres-1` container before the next run.
- `schema.sql` runs with `spring.sql.init.mode=always`, so every app start recreates `accounts`/`transactions` from empty — each script run starts from a clean slate.
