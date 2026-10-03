# htap-router

`htap-router` is a Java 21 / Spring Boot Hybrid Transactional/Analytical Processing (HTAP) routing service. It splits workload between a relational database (PostgreSQL) for low-latency operational writes and point lookups, and an embedded column-oriented analytical engine (DuckDB) for high-throughput aggregations.

---

## Tech Stack

* **Language/Runtime:** Java 21 (Virtual Threads, `StructuredTaskScope`)
* **Framework:** Spring Boot 4.1 / Tomcat
* **OLTP Database:** PostgreSQL 18 (via Docker Compose)
* **OLAP Database:** Embedded DuckDB (in-memory, JDBC `jdbc:duckdb:`)
* **Data Access / ORM:** jOOQ (type-safe SQL builder for PostgreSQL)
* **Connection Pooling:** HikariCP
* **Build System:** Gradle

---

## Core Architecture & Key Components

```
                     +---------------------------+
                     |    Client / HTTP Layer    |
                     +-------------+-------------+
                                   |
                         +---------v---------+
                         | HtapRouterService |
                         +----+---------+----+
                              |         |
           [ OLTP Path ]      |         |      [ OLAP Path ]
    (Point Lookups / Writes)  |         |  (Aggregations / Analytics)
                              |         |
             +----------------v--+   +--v------------------------+
             | DashboardService  |   |  DuckDBAnalyticsService   |
             | (Virtual Threads) |   | (In-Memory DuckDB Engine) |
             +--------+----------+   +--------------+------------+
                      |                             ^
                      |                             |
             +--------v----------+          [ ETL / Sync ]
             | PostgreSQL (OLTP) +------------------+
             +-------------------+

```

### 1. Operational Engine (PostgreSQL + Structured Concurrency)

* **Data Model:**
  * `accounts` (`id`, `owner_name`, `balance`, `created_at`)
  * `transactions` (`id`, `seq`, `account_id`, `amount`, `transaction_type`, `created_at`)

* **Structured Concurrency (`DashboardService`):**
  * Uses Java 21 Virtual Threads and `StructuredTaskScope` to fetch account details, recent transactions, and aggregate metrics concurrently when building the account dashboard.

### 2. Analytical Engine (DuckDB)

* **Configuration (`DuckDBConfig`):**
  * Instantiates a single shared in-memory DuckDB connection (`jdbc:duckdb:`) as the root connection, and initializes the columnar `duck_transactions` table.

* **Sync Engine (`DuckDBAnalyticsService.syncFromPostgres()`):**
  * Pulls new rows from PostgreSQL via jOOQ, incrementally, using a monotonic `seq` identity column as the watermark.
  * Loads rows into DuckDB through a `DuckDBAppender` on a connection duplicated from the root (`DuckDBConnection.duplicate()`), giving the bulk load MVCC snapshot isolation — concurrent readers never see a half-loaded table.
  * Overlapping syncs are rejected (via `ReentrantLock.tryLock()`) rather than queued, to avoid blocking a virtual thread's carrier for the duration.

* **Aggregations (`DuckDBAnalyticsService.getSystemAnalytics()`):**
  * Computes system-wide volume, transaction counts, average size, and categorical volume breakdowns natively inside DuckDB, against its own duplicated connection (independent of any in-flight sync).

### 3. Query Router (`HtapRouterService`)

* Evaluates inbound query types defined by `QueryType`:
  * `OLTP_POINT_LOOKUP`: routes operational queries to `DashboardService` / PostgreSQL.
  * `OLAP_AGGREGATE`: routes analytical queries to `DuckDBAnalyticsService` / DuckDB.

---

## API Surface

| Method | Endpoint | Description | Target Engine |
| --- | --- | --- | --- |
| `POST` | `/api/accounts?name={name}&initialBalance={bal}` | Creates account and initial deposit transaction | PostgreSQL |
| `GET` | `/api/accounts/{id}/dashboard` | Concurrently fetches dashboard info via Virtual Threads | PostgreSQL |
| `POST` | `/api/analytics/sync` | Incrementally replicates new PostgreSQL records into DuckDB | PostgreSQL -> DuckDB |
| `GET` | `/api/analytics/summary` | Serves analytical metrics and volume breakdowns | DuckDB |

---

## Running the Project

### Prerequisites

* Java 21+
* Docker (for PostgreSQL via Docker Compose)

### Steps

```bash
# from the project root
./gradlew bootRun
```

Spring Boot's Docker Compose support (`spring-boot-docker-compose`) auto-detects `compose.yaml` and starts the PostgreSQL container on application startup — no manual `docker compose up` needed. `schema.sql` runs automatically on boot (`spring.sql.init.mode=always`), recreating `accounts` and `transactions`.

The app listens on `http://localhost:8080` by default.

### Try it out

```bash
# Create an account
curl -X POST "http://localhost:8080/api/accounts?name=Ada&initialBalance=100.00"

# Fetch its dashboard (use the id returned above)
curl "http://localhost:8080/api/accounts/{id}/dashboard"

# Sync new transactions into DuckDB
curl -X POST "http://localhost:8080/api/analytics/sync"

# Fetch OLAP analytics
curl "http://localhost:8080/api/analytics/summary"
```

### Tests

```bash
./gradlew test
```

`HtapRouterApplicationTests#contextLoads` requires the Postgres container to be reachable (via Docker Compose support), same as `bootRun`.
