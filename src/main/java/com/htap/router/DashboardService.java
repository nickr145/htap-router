package com.htap.router;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

@Service 
public class DashboardService {

    private final DSLContext dsl;

    public DashboardService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @SuppressWarnings("preview")
    public DashboardDTO.DashboardResponse getDashboardData(String accountId) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // Fork Virtual Thread 1: Fetch Account
            StructuredTaskScope.Subtask<DashboardDTO.AccountSummary> accountTask = scope.fork(() ->
                dsl.select(DSL.field("id", String.class), DSL.field("owner_name", String.class), DSL.field("balance", BigDecimal.class))
                   .from(DSL.table("accounts"))
                   .where(DSL.field("id").eq(accountId))
                   .fetchOptional()
                   .map(r -> new DashboardDTO.AccountSummary(r.value1(), r.value2(), r.value3()))
                   .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId))
            );

            // Fork Virtual Thread 2: Fetch Recent Transactions
            StructuredTaskScope.Subtask<List<DashboardDTO.TransactionRecord>> transactionsTask = scope.fork(() ->
                dsl.select(DSL.field("id", String.class), DSL.field("amount", BigDecimal.class), DSL.field("transaction_type", String.class), DSL.field("created_at", OffsetDateTime.class))
                   .from(DSL.table("transactions"))
                   .where(DSL.field("account_id").eq(accountId))
                   .orderBy(DSL.field("created_at").desc())
                   .limit(10)
                   .fetch()
                   .map(r -> new DashboardDTO.TransactionRecord(r.value1(), r.value2(), r.value3(), r.value4()))
            );

            // Fork Virtual Thread 3: Compute Summary Metrics
            StructuredTaskScope.Subtask<DashboardDTO.Metrics> metricsTask = scope.fork(() -> {
                int count = dsl.fetchCount(DSL.table("transactions"), DSL.field("account_id").eq(accountId));
                BigDecimal sum = dsl.select(DSL.sum(DSL.field("amount", BigDecimal.class)))
                                    .from(DSL.table("transactions"))
                                    .where(DSL.field("account_id").eq(accountId))
                                    .fetchOneInto(BigDecimal.class);
                return new DashboardDTO.Metrics(count, sum != null ? sum : BigDecimal.ZERO);
            });

            // Join all virtual threads concurrently
            scope.join();
            scope.throwIfFailed();

            return new DashboardDTO.DashboardResponse(accountTask.get(), transactionsTask.get(), metricsTask.get());
        }
    }
    
}
