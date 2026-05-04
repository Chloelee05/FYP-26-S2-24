package com.auction.dao;

import com.auction.model.Role;
import com.auction.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SCRUM-188: verifies {@link UserDAO} {@code ResultSet} → {@link User} mapping without DB / {@code DBUtil}.
 */
@DisplayName("UserDAO ResultSet mapping (SCRUM-188)")
class UserDAOMappingTest {

    @Test
    @DisplayName("Profile row: maps id, username, email, role, 2FA, encrypted PII; omits password when includePassword=false")
    void mapsProfileRowWithoutPassword() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("id")).thenReturn(7);
        when(rs.getString("username")).thenReturn("alice");
        when(rs.getString("email")).thenReturn("alice@example.com");
        when(rs.getInt("role_id")).thenReturn(2);
        when(rs.getInt("status_id")).thenReturn(1);
        when(rs.getBoolean("two_factor_enabled")).thenReturn(false);
        when(rs.getString("two_factor_secret")).thenReturn(null);
        when(rs.getString("phone_encrypted")).thenReturn("encPhone");
        when(rs.getString("address_encrypted")).thenReturn("encAddr");
        when(rs.getString("profile_image_url")).thenReturn("https://cdn.example.com/a.png");

        User u = UserDAO.mapUserFromResultSet(rs, false);

        assertEquals(7, u.getId());
        assertEquals("alice", u.getUsername());
        assertEquals("alice@example.com", u.getEmail());
        assertNull(u.getPassword());
        assertEquals(Role.BUYER, u.getRole());
        assertEquals(1, u.getStatusId());
        assertFalse(u.isTwoFactorEnabled());
        assertEquals("encPhone", u.getPhoneEncrypted());
        assertEquals("encAddr", u.getAddressEncrypted());
        assertEquals("https://cdn.example.com/a.png", u.getProfileImageUrl());
    }

    @Test
    @DisplayName("Login row: includePassword=true loads password hash")
    void mapsLoginRowWithPassword() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("id")).thenReturn(1);
        when(rs.getString("username")).thenReturn("bob");
        when(rs.getString("email")).thenReturn("bob@test.com");
        when(rs.getString("password")).thenReturn("1$salt$hash");
        when(rs.getInt("role_id")).thenReturn(3);
        when(rs.getInt("status_id")).thenReturn(1);
        when(rs.getBoolean("two_factor_enabled")).thenReturn(true);
        when(rs.getString("two_factor_secret")).thenReturn("secretCipher");
        when(rs.getString("phone_encrypted")).thenReturn(null);
        when(rs.getString("address_encrypted")).thenReturn(null);
        when(rs.getString("profile_image_url")).thenReturn(null);

        User u = UserDAO.mapUserFromResultSet(rs, true);

        assertEquals("1$salt$hash", u.getPassword());
        assertEquals(Role.SELLER, u.getRole());
        assertEquals(1, u.getStatusId());
        assertTrue(u.isTwoFactorEnabled());
        assertEquals("secretCipher", u.getTwoFactorSecret());
        assertNull(u.getPhoneEncrypted());
        assertNull(u.getAddressEncrypted());
        assertNull(u.getProfileImageUrl());
    }
}
