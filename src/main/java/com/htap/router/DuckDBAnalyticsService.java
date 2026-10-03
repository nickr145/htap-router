package com.htap.router;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;

@Service 
public class DuckDBAnalyticsService {

    private final Connection duckConn;
    private final DSLContext postgresDsl;
    private volatile OffsetDateTime lastSyncedAt = OffsetDateTime.MIN;

    public DuckDBAnalyticsService(@Qualifier("duckDbConnection") Connection duckConn, DSLContext postgresDsl) {
        this.duckConn = duckConn;
        this.postgresDsl = postgresDsl;
    }

    // Syncs transactional records from PostgreSQL (OLTP) into DuckDB (OLAP), incrementally by created_at watermark
    public synchronized int syncFromPostgres() throws SQLException {
        var records = postgresDsl.select(
            DSL.field("id", String.class),
            DSL.field("account_id", String.class),
            DSL.field("amount", BigDecimal.class),
            DSL.field("transaction_type", String.class),
            DSL.field("created_at", OffsetDateTime.class)
        )
        .from(DSL.table("transactions"))
        .where(DSL.field("created_at", OffsetDateTime.class).gt(lastSyncedAt))
        .fetch();

        if (records.isEmpty()) {
            return 0;
        }

        String insertSql = "insert into duck_transactions (id, account_id, amount, transaction_type, created_at) values (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = duckConn.prepareStatement(insertSql)) {
            for (var r : records) {
                pstmt.setString(1, r.value1());
                pstmt.setString(2, r.value2());
                pstmt.setBigDecimal(3, r.value3());
                pstmt.setString(4, r.value4());
                pstmt.setTimestamp(5, Timestamp.from(r.value5().toInstant()));
                pstmt.addBatch();
            }
            int[] res = pstmt.executeBatch();

            for (var r : records) {
                if (r.value5().isAfter(lastSyncedAt)) {
                    lastSyncedAt = r.value5();
                }
            }

            return res.length;
        }
    }

    // Executes fast OLAP aggregations on DuckDB
    public synchronized AnalyticsDTO.SystemAnalyticsResponse getSystemAnalytics() throws SQLException {
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

        try (Statement stmt = duckConn.createStatement(); ResultSet rs = stmt.executeQuery(overallSql)) {
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
        try (Statement stmt = duckConn.createStatement(); ResultSet rs = stmt.executeQuery(breakdownSql)) {
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
