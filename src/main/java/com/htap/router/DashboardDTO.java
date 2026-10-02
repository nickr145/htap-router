package com.htap.router;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class DashboardDTO {
    public record AccountSummary(String id, String ownerName, BigDecimal balance) {}
    public record TransactionRecord(String id, BigDecimal amount, String type, OffsetDateTime createdAt) {}
    public record Metrics(int totalTransactions, BigDecimal totalDeposited) {}

    public record DashboardResponse(AccountSummary account, List<TransactionRecord> recentTransactions, Metrics metrics) {}
}
