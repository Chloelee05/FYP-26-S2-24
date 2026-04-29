package com.auction.util;

import java.sql.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DBUtil {
    private static final HikariConfig config = new HikariConfig();
    private static final HikariDataSource dataSource;

    static{
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/auction_db");
        config.setUsername("postgres");
        config.setPassword("admin");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(10);
        config.setConnectionTimeout(30000); // 30 seconds
        config.setIdleTimeout(600000); // 10 minutes
        config.setMaxLifetime(1800000); // 30 minutes
        dataSource = new HikariDataSource(config);
    }

    public static Connection connectDB() throws Exception{
        return dataSource.getConnection();
    }
/*    private static final String URL = "jdbc:mysql://localhost:3306/auction_db";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "admin";

    public static Connection connectDB() throws Exception {
        //jdbc 4.0+ doesn't require
/       try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }


        try{
            return DriverManager.getConnection(URL, USERNAME, PASSWORD);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }*/
}
