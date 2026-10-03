package com.htap.router;

import org.springframework.stereotype.Service;

@Service 
public class HtapRouterService {

    public enum QueryType { OLTP_POINT_LOOKUP, OLAP_AGGREGATE }

    private final DashboardService postgresDashboardService;
    private final DuckDBAnalyticsService duckDBAnalyticsService;

    public HtapRouterService(DashboardService postgresDashboardService, DuckDBAnalyticsService duckDBAnalyticsService) {
        this.postgresDashboardService = postgresDashboardService;
        this.duckDBAnalyticsService = duckDBAnalyticsService;
    }

    public Object routeQuery(QueryType type, String accountId) throws Exception {
        return switch (type) {
            case OLTP_POINT_LOOKUP -> {
                // Route point lookup / operational query to PostgreSQL via Virtual Threads
                yield postgresDashboardService.getDashboardData(accountId);
            }
            case OLAP_AGGREGATE -> {
                // Route analytical query to DuckDB
                yield duckDBAnalyticsService.getSystemAnalytics();
            }
        };
    }
    
}
