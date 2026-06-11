package com.auction.util;

import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * JDBC pool for PostgreSQL (local or hosted).
 * <p>
 * Configure before starting Tomcat (or {@code mvn cargo:run}):
 * </p>
 * <ul>
 *   <li>{@code AUCTION_DB_URL} — full JDBC URL, e.g.
 *       {@code jdbc:postgresql://host:5432/auction_db?sslmode=require}</li>
 *   <li>{@code AUCTION_DB_USER} — default {@code postgres}</li>
 *   <li>{@code AUCTION_DB_PASSWORD} — database password</li>
 * </ul>
 * If {@code AUCTION_DB_URL} is unset, falls back to local {@code localhost:5432/auction_db}.
 */
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
            config.setJdbcUrl(jdbcUrl());
            config.setUsername(dbUser());
            config.setPassword(dbPassword());
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

    private static String jdbcUrl() {
        String url = firstNonBlank(System.getenv("AUCTION_DB_URL"));
        if (url != null) {
            return url;
        }
        return "jdbc:postgresql://localhost:5432/auction_db";
    }

    private static String dbUser() {
        String user = firstNonBlank(System.getenv("AUCTION_DB_USER"));
        return user != null ? user : "postgres";
    }

    private static String dbPassword() {
        String pw = firstNonBlank(System.getenv("AUCTION_DB_PASSWORD"));
        return pw != null ? pw : "chloelee";
    }

    private static String firstNonBlank(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public static Connection connectDB() throws Exception {
        return getDataSource().getConnection();
    }

    @FunctionalInterface
    public interface TransactionBlock<T> {
        T execute(Connection conn) throws Exception;
    }

    public static <T> T runInTransaction(TransactionBlock<T> block) throws Exception {
        try (Connection conn = connectDB()) {
            conn.setAutoCommit(false);
            try {
                T result = block.execute(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }
}
