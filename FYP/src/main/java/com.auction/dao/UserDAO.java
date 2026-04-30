package com.auction.dao;

import com.auction.model.Role;
import com.auction.model.User;
import com.auction.model.Status;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class UserDAO {
    public boolean checkUser(String username){
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "SELECT 1 FROM users WHERE username = ? LIMIT 1";
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

            String sqlString = "SELECT 1 FROM users WHERE email = ? LIMIT 1";
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

            String sqlString = "INSERT INTO users (username, email, password, role_id, status_id) " +
                    "VALUES(?, ?, ?, ?, ?) ";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, user.getUsername());
            pStatement.setString(2, user.getEmail());
            pStatement.setString(3, user.getPassword());
            pStatement.setInt(4, user.getRole().getId());
            pStatement.setInt(5, Status.ACTIVE.getId());

            int rowsAffected = pStatement.executeUpdate();
            return rowsAffected > 0;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    } 

    public boolean updateStatus(int id, int status)
    {
        try(Connection conn = DBUtil.connectDB()) {
            String sqlString = "UPDATE users SET status_id = ? WHERE ID = ?";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setInt(1, status);
            pStatement.setInt(2, id);
            int rowsAffected = pStatement.executeUpdate();
            return rowsAffected == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<User> viewAllUsers(){
        try(Connection conn = DBUtil.connectDB()) {
            List<User> userList = new ArrayList<>();
            String sqlString = "SELECT * FROM users";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            ResultSet resultSet = pStatement.executeQuery();

            while(resultSet.next())
            {
                User user = new User();
                user.setId(resultSet.getInt("id"));
                user.setUsername(resultSet.getString("username"));
                user.setEmail(resultSet.getString("email"));
                user.setRole(Role.getRole(resultSet.getInt("role_id")));
                userList.add((user));
            }
            return userList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
