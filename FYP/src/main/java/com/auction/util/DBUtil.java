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
 *   <li>{@code AUCTION_DB_PASSWORD} — database password; required, there is no default</li>
 * </ul>
 * If {@code AUCTION_DB_URL} is unset, falls back to local {@code localhost:5432/auction_db}.
 *
 * <p>The URL, user and database name have local defaults because none of them is a secret.
 * The password deliberately has none: a default password in this file would be a credential
 * committed to the repository, readable by anyone with the source. An unset
 * {@code AUCTION_DB_PASSWORD} therefore fails immediately with an explanation instead of
 * guessing, since a guessed password only produces an authentication error further on.</p>
 *
 * <p>Every database connection in the application comes from here. Connections are pooled
 * by HikariCP rather than opened per query, because opening a PostgreSQL connection costs
 * a TCP handshake and authentication round trip that would otherwise be paid on every
 * request. Callers borrow a connection with {@link #connectDB()} and must close it, which
 * returns it to the pool instead of really closing it, so try-with-resources is the
 * expected pattern.</p>
 */
public class DBUtil {
    /** Environment variable holding the database password. Never defaulted in source. */
    private static final String DB_PASSWORD_ENV = "AUCTION_DB_PASSWORD";

    private static HikariDataSource dataSource;

    /**
     * Builds the pool on first use and reuses it thereafter.
     *
     * <p>Synchronized so that two requests arriving together cannot each build a pool. The
     * cost is only paid on the first call, since every later one finds the field set.</p>
     *
     * <p>The pool is capped at ten connections with the same minimum, so it stays fully
     * warm rather than opening and closing connections as load varies. That ceiling is
     * chosen to suit the small hosted instance the project deploys to; asking for more
     * connections than the database is configured to accept would fail at peak rather than
     * at startup. Prepared statement caching is on, which matters because the DAOs run the
     * same handful of queries repeatedly.</p>
     */
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

    /**
     * The database password, which must come from the environment.
     *
     * <p>An explicitly empty value is honoured, because a local PostgreSQL set to {@code trust}
     * authentication genuinely needs no password. Leaving the variable out altogether is a
     * misconfiguration rather than a choice, so it is reported as one.</p>
     */
    private static String dbPassword() {
        String pw = firstNonBlank(System.getenv(DB_PASSWORD_ENV));
        if (pw != null) {
            return pw;
        }
        if (System.getenv(DB_PASSWORD_ENV) != null) {
            return "";
        }
        throw new IllegalStateException(DB_PASSWORD_ENV + " is not set, so the application cannot "
                + "connect to the database. Set it in your run configuration or shell before "
                + "starting Tomcat, e.g. export " + DB_PASSWORD_ENV + "=<your local postgres "
                + "password>. Set it to an empty value if your local PostgreSQL uses trust "
                + "authentication. There is no built-in default, because a password must not be "
                + "stored in the source code.");
    }

    private static String firstNonBlank(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Borrows a connection from the pool. Closing it hands it back rather than dropping it,
     * so callers should always use try-with-resources; a leaked connection is permanently
     * lost from a pool of ten.
     */
    public static Connection connectDB() throws Exception {
        return getDataSource().getConnection();
    }

    /** The body of a transaction, written as a lambda that receives the connection. */
    @FunctionalInterface
    public interface TransactionBlock<T> {
        T execute(Connection conn) throws Exception;
    }

    /**
     * Runs {@code block} inside one transaction: commits if it returns, rolls back if it
     * throws, and returns the connection to the pool either way.
     *
     * <p>Used wherever several writes have to succeed or fail together, such as concluding
     * an auction, where the status change, the winner and the new order must not come
     * apart. Auto-commit is switched off for the duration, so nothing the block writes is
     * visible to other connections until it commits.</p>
     */
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
