package com.htap.router;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
public class AccountRepository {

    private final DSLContext dsl;

    public AccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional 
    public void createAccount(String ownerName, BigDecimal initialBalance) {
        String accountId = UUID.randomUUID().toString();

        dsl.insertInto(DSL.table("accounts"))
           .columns(DSL.field("id"), DSL.field("owner_name"), DSL.field("balance"))
           .values(accountId, ownerName, initialBalance)
           .execute();
        
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            dsl.insertInto(DSL.table("transactions"))
                .columns(DSL.field("id"), DSL.field("account_id"), DSL.field("amount"), DSL.field("transaction_type"))
                .values(UUID.randomUUID().toString(), accountId, initialBalance, "DEPOSIT")
                .execute();
        }
    }
    
}
