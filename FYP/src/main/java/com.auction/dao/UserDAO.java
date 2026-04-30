package com.auction.dao;

import com.auction.model.Role;
import com.auction.model.User;
import com.auction.model.Status;
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

            String sqlString = "INSERT INTO user (username, email, password, role_id, status_id) " +
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

    public User getUserByEmail(String email) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT id, username, email, password, role_id FROM user WHERE email = ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                User user = new User(
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("password"),
                        Role.fromId(rs.getInt("role_id"))
                );
                user.setId(rs.getInt("id"));
                user.setTwoFactorEnabled(rs.getBoolean("two_factor_enabled"));
                user.setTwoFactorSecret(rs.getString("two_factor_secret"));
                return user;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean enableTwoFactor(String email, String encryptedSecret) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE user SET two_factor_enabled = TRUE, two_factor_secret = ? WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, encryptedSecret);
            ps.setString(2, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean disableTwoFactor(String email) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE user SET two_factor_enabled = FALSE, two_factor_secret = NULL WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean updatePassword(String email, String hashedPassword) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE user SET password = ? WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, hashedPassword);
            ps.setString(2, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean updateStatus(User user, int id, int status)
    {
        try(Connection conn = DBUtil.connectDB()) {
            String sqlString = "UPDATE user SET status_id = ? WHERE ID = ?";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setInt(1, status);
            pStatement.setInt(2, id);
            int rowsAffected = pStatement.executeUpdate();
            return rowsAffected == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
