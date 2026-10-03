package com.htap.router;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController 
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final DuckDBAnalyticsService duckDbAnalyticsService;
    private final HtapRouterService routerService;

    public AnalyticsController(DuckDBAnalyticsService duckDbAnalyticsService, HtapRouterService routerService) {
        this.duckDbAnalyticsService = duckDbAnalyticsService;
        this.routerService = routerService;
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncPostgresToDuckDB() throws Exception {
        int syncedCount = duckDbAnalyticsService.syncFromPostgres();
        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "recordsSynced", syncedCount,
            "engine", "DuckDB"
        ));
    }

    @GetMapping("/summary")
    public ResponseEntity<Object> getSystemAnalytics() throws Exception {
        Object resp = routerService.routeQuery(HtapRouterService.QueryType.OLAP_AGGREGATE, null);
        return ResponseEntity.ok(resp);
    }
    
}
