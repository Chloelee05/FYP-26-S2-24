package com.auction.dao;

import com.auction.model.Role;
import com.auction.model.User;
import com.auction.model.Status;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /**
     * Loads a user row for login (includes password hash).
     */
    public User getUserByEmail(String email) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT id, username, email, password, role_id, two_factor_enabled, two_factor_secret, "
                    + "phone_encrypted, address_encrypted "
                    + "FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapUserFromResultSet(rs, true);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads profile fields for the signed-in user (password column is not selected).
     */
    public User getUserById(int id) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT id, username, email, role_id, two_factor_enabled, two_factor_secret, "
                    + "phone_encrypted, address_encrypted "
                    + "FROM users WHERE id = ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapUserFromResultSet(rs, false);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Package-private for row-mapping unit tests without touching {@link DBUtil}. */
    static User mapUserFromResultSet(ResultSet rs, boolean includePassword) throws SQLException {
        User user = new User();
        user.setId(rs.getInt("id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        if (includePassword) {
            user.setPassword(rs.getString("password"));
        }
        user.setRole(Role.getRole(rs.getInt("role_id")));
        user.setTwoFactorEnabled(rs.getBoolean("two_factor_enabled"));
        user.setTwoFactorSecret(rs.getString("two_factor_secret"));
        user.setPhoneEncrypted(rs.getString("phone_encrypted"));
        user.setAddressEncrypted(rs.getString("address_encrypted"));
        return user;
    }

    public boolean enableTwoFactor(String email, String encryptedSecret) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE users SET two_factor_enabled = TRUE, two_factor_secret = ? WHERE email = ?";
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
            String sql = "UPDATE users SET two_factor_enabled = FALSE, two_factor_secret = NULL WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean updatePassword(String email, String hashedPassword) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE users SET password = ? WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, hashedPassword);
            ps.setString(2, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * PDPA-aligned account closure: removes identifying data in place (email, username, password,
     * phone, address, 2FA secrets) and marks the row {@link Status#DELETED}. The primary key is
     * retained so auction/bid foreign keys remain valid without exposing the data subject.
     */
    public boolean deleteAccount(int userId) {
        try (Connection conn = DBUtil.connectDB()) {
            return deleteAccountWithConnection(conn, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Same as {@link #deleteAccount(int)} but uses an existing connection (for unit tests with a mock).
     */
    boolean deleteAccountWithConnection(Connection conn, int userId) throws SQLException {
        String token = UUID.randomUUID().toString().replace("-", "");
        String shortTok = token.substring(0, Math.min(16, token.length()));
        String anonymizedEmail = "deleted_" + userId + "_" + shortTok + "@invalid.auction.local";
        String anonymizedUsername = "deleted_u" + userId + "_" + shortTok;
        String randomSecret = UUID.randomUUID().toString() + token;
        String newPasswordHash = SecurityUtil.hashPassword(randomSecret);

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        String sql = "UPDATE users SET email = ?, username = ?, password = ?, "
                + "phone_encrypted = NULL, address_encrypted = NULL, "
                + "two_factor_enabled = FALSE, two_factor_secret = NULL, "
                + "status_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, anonymizedEmail);
            ps.setString(2, anonymizedUsername);
            ps.setString(3, newPasswordHash);
            ps.setInt(4, Status.DELETED.getId());
            ps.setInt(5, userId);
            boolean updated = ps.executeUpdate() == 1;
            conn.commit();
            return updated;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
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
