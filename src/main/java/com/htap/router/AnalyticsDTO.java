package com.htap.router;

import java.util.List;
import java.math.BigDecimal;

public class AnalyticsDTO {

    public record TypeSummary(String transactionType, long totalCount, BigDecimal totalVolume, BigDecimal avgAmount) {}

    public record SystemAnalyticsResponse(
        long totalTransactionCount,
        BigDecimal totalSystemVolume,
        BigDecimal avgTransactionSize,
        List<TypeSummary> breakdownByType
    ) {}
    
}
