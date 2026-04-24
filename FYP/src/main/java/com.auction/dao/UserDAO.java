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
    } //for username validation

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
    } // for email validation

    public boolean insertUser(User user)
    {
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "INSERT INTO user (username, email, password, roles_id, status_id) " +
                    "VALUES(?, ?, ?, ?, ?) ";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, user.getUsername());
            pStatement.setString(2, user.getEmail());
            pStatement.setString(3, user.getPassword());
            if(user.getRole().equalsIgnoreCase("seller"))
            {
                pStatement.setInt(4, 3);
            }
            else{
                pStatement.setInt(4, 2);
            }
            pStatement.setInt(5, 1);

            int rowsAffected = pStatement.executeUpdate();
            return rowsAffected > 0;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
