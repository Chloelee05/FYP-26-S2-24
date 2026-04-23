package com.auction.dao;

import com.auction.model.User;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserDAO {
    public boolean checkUser(String username){
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "SELECT 1 FROM user WHERE username = ? LIMIT 1";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, username);

            try(ResultSet resultSet = pStatement.executeQuery()) {
                return resultSet.next();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean checkEmail(String email){
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "SELECT 1 FROM user WHERE email = ? LIMIT 1";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, email);

            try(ResultSet resultSet = pStatement.executeQuery()) {
                return resultSet.next();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean insertUser()
    {
        return false;
    }
}
