package com.htap.router;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController 
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public ResponseEntity<String> createAccount(@RequestParam String name, @RequestParam BigDecimal initialBalance) {
        accountRepository.createAccount(name, initialBalance);
        return ResponseEntity.ok("Account created successfully");
    }
    
}
