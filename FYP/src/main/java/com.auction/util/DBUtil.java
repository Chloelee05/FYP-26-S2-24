package com.auction.util;

import java.sql.*;

public class DBUtil {
    private static final String URL = "jdbc:mysql://localhost:3306/auction_db";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "admin";

    public static Connection connectDB() throws Exception {
        //jdbc 4.0+
/*        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }*/

        try{
            return DriverManager.getConnection(URL, USERNAME, PASSWORD);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
