package com.htap.router;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final DashboardService dashboardService;

    public AccountController(AccountRepository accountRepository, DashboardService dashboardService) {
        this.accountRepository = accountRepository;
        this.dashboardService = dashboardService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createAccount(@RequestParam String name, @RequestParam BigDecimal initialBalance) {
        if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("initialBalance must not be negative");
        }
        String accountId = accountRepository.createAccount(name, initialBalance);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", accountId));
    }

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<DashboardDTO.DashboardResponse> getDashboard(@PathVariable String id) throws Exception {
        return ResponseEntity.ok(dashboardService.getDashboardData(id));
    }

}
