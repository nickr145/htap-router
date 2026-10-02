package com.htap.router;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

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
    public ResponseEntity<String> createAccount(@RequestParam String name, @RequestParam BigDecimal initialBalance) {
        accountRepository.createAccount(name, initialBalance);
        return ResponseEntity.ok("Account created successfully");
    }

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<DashboardDTO.DashboardResponse> getDashboard(@PathVariable String id) throws Exception {
        return ResponseEntity.ok(dashboardService.getDashboardData(id));
    }
    
}
