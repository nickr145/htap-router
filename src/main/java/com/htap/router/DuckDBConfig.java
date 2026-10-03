package com.htap.router;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Configuration 
public class DuckDBConfig {

    @Bean(name = "duckDbConnection")
    public Connection duckDbConnection() throws SQLException {
        // In-memory DuckDB instance shared across application execution
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                create table if not exists duck_transactions (
                    id varchar(36),
                    account_id varchar(36),
                    amount decimal(15, 2),
                    transaction_type varchar(20),
                    created_at timestamp
                );
            """);
        }
        return conn;
    }
    
}
