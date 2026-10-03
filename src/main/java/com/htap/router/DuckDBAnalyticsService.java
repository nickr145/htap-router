package com.htap.router;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DuckDBAnalyticsService {

    private final DuckDBConnection rootConn;
    private final DSLContext postgresDsl;
    private final ReentrantLock syncLock = new ReentrantLock();
    private volatile long lastSyncedSeq = 0;

    public DuckDBAnalyticsService(@Qualifier("duckDbConnection") Connection duckConn, DSLContext postgresDsl) {
        this.rootConn = (DuckDBConnection) duckConn;
        this.postgresDsl = postgresDsl;
    }

    // Syncs transactional records from PostgreSQL (OLTP) into DuckDB (OLAP), incrementally by seq watermark.
    // seq is a Postgres identity column, so it's monotonic and race-free, unlike created_at (commit order can
    // differ from timestamp order). tryLock rejects an overlapping sync instead of blocking a virtual thread,
    // which would otherwise pin its carrier thread for the duration.
    public int syncFromPostgres() throws SQLException {
        if (!syncLock.tryLock()) {
            return -1;
        }
        try {
            var records = postgresDsl.select(
                DSL.field("id", String.class),
                DSL.field("seq", Long.class),
                DSL.field("account_id", String.class),
                DSL.field("amount", BigDecimal.class),
                DSL.field("transaction_type", String.class),
                DSL.field("created_at", OffsetDateTime.class)
            )
            .from(DSL.table("transactions"))
            .where(DSL.field("seq", Long.class).gt(lastSyncedSeq))
            .orderBy(DSL.field("seq", Long.class))
            .fetch();

            if (records.isEmpty()) {
                return 0;
            }

            long maxSeq = lastSyncedSeq;
            // A fresh duplicated connection gives this bulk load MVCC snapshot isolation: readers on other
            // connections keep seeing the pre-sync data until this transaction commits, instead of a half-loaded table.
            try (var conn = (DuckDBConnection) rootConn.duplicate()) {
                conn.setAutoCommit(false);
                try (DuckDBAppender appender = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "duck_transactions")) {
                    for (var r : records) {
                        appender.beginRow();
                        appender.append(r.value1());
                        appender.append(r.value3());
                        appender.appendBigDecimal(r.value4());
                        appender.append(r.value5());
                        appender.appendLocalDateTime(LocalDateTime.ofInstant(r.value6().toInstant(), ZoneOffset.UTC));
                        appender.endRow();
                        maxSeq = Math.max(maxSeq, r.value2());
                    }
                    appender.flush();
                }
                conn.commit();
            }

            lastSyncedSeq = maxSeq;
            return records.size();
        } finally {
            syncLock.unlock();
        }
    }

    // Executes fast OLAP aggregations on DuckDB against a consistent MVCC snapshot, independent of any in-flight sync
    public AnalyticsDTO.SystemAnalyticsResponse getSystemAnalytics() throws SQLException {
        try (var conn = rootConn.duplicate()) {
            String overallSql = """
                select
                    count(*) as total_count,
                    coalesce(sum(amount), 0) as total_volume,
                    coalesce(avg(amount), 0) as avg_amount
                from duck_transactions;
            """;

            long count = 0;
            BigDecimal volume = BigDecimal.ZERO;
            BigDecimal avg = BigDecimal.ZERO;

            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(overallSql)) {
                if (rs.next()) {
                    count = rs.getLong("total_count");
                    volume = rs.getBigDecimal("total_volume");
                    avg = rs.getBigDecimal("avg_amount");
                }
            }

            String breakdownSql = """
                select
                    transaction_type,
                    count(*) as type_count,
                    sum(amount) as type_volume,
                    avg(amount) as type_avg
                from duck_transactions
                group by transaction_type;
            """;

            List<AnalyticsDTO.TypeSummary> breakdown = new ArrayList<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(breakdownSql)) {
                while (rs.next()) {
                    breakdown.add(new AnalyticsDTO.TypeSummary(
                        rs.getString("transaction_type"),
                        rs.getLong("type_count"),
                        rs.getBigDecimal("type_volume"),
                        rs.getBigDecimal("type_avg")
                    ));
                }
            }

            return new AnalyticsDTO.SystemAnalyticsResponse(count, volume, avg, breakdown);
        }
    }

}
