package com.auction.dao;

import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Rows left behind by an encryption-key change.
 *
 * <p>{@code auto_bids} rows written before {@code AUCTION_AES_SECRET} was provisioned cannot
 * be opened with the current key. {@code processAutoBids} already skipped them, but the
 * single-row read behind {@code GET /api/auto-bid} propagated the failure as a
 * {@link RuntimeException}, and the servlet has no handler — so a buyer merely opening an
 * affected auction got a 500.</p>
 */
@DisplayName("AutoBidDAO — rows encrypted under a superseded key")
class AutoBidDAOStaleKeyTest {

    private Connection conn;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBigDecimal("bid_increment")).thenReturn(AutoBidDAO.MIN_INCREMENT);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
    }

    private AutoBidDAO.AutoBidRow read() throws Exception {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(conn);
            return new AutoBidDAO().getAutoBidForUser(5L, 3);
        }
    }

    @Test
    @DisplayName("an undecryptable row reads as no auto-bid instead of throwing")
    void undecryptableRowReadsAsAbsent() throws Exception {
        // Valid Base64 of the right shape, but produced under a different key.
        when(rs.getString("max_amount_enc"))
                .thenReturn("O3I2O6chxv/VN7xUBgbW0iHkQZ0aHnHcW8Zc9m0P3sA=");

        assertNull(read(), "a row that will not decrypt must not become a 500");
    }

    @Test
    @DisplayName("a corrupt ciphertext reads as no auto-bid too")
    void corruptCiphertextReadsAsAbsent() throws Exception {
        when(rs.getString("max_amount_enc")).thenReturn("not-base64-at-all");

        assertNull(read());
    }

    @Test
    @DisplayName("a row under the current key still decrypts normally")
    void currentKeyStillWorks() throws Exception {
        when(rs.getString("max_amount_enc")).thenReturn(SecurityUtil.encrypt("250.00"));

        AutoBidDAO.AutoBidRow row = read();
        assertNotNull(row);
        assertEquals(0, row.getMaxAmount().compareTo(new java.math.BigDecimal("250.00")));
    }
}
