package com.auction.util;

import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DBUtil {
    private static HikariDataSource dataSource;

    private static synchronized HikariDataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            try {
                Class.forName("org.postgresql.Driver");
            } catch (ClassNotFoundException e) {
                throw new ExceptionInInitializerError(e);
            }
            config.setDriverClassName("org.postgresql.Driver");
            config.setJdbcUrl("jdbc:postgresql://localhost:5432/auction_db");
            config.setUsername("postgres");
            config.setPassword("chloelee");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(10);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    public static Connection connectDB() throws Exception {
        return getDataSource().getConnection();
    }
}