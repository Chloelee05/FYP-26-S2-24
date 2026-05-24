package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Data-access layer for buyer reports against sellers.
 *
 * <p><b>One report per auction per buyer:</b> Enforced by the
 * {@code UNIQUE (reporter_user_id, auction_id)} constraint on {@code seller_reports}.
 * A pre-check is done first so the servlet receives a clean {@link ReportResult}
 * rather than a raw constraint-violation exception.</p>
 *
 * <p><b>IDOR prevention:</b> {@code reportedUserId} (seller) is resolved from the DB
 * inside the transaction — never taken from the request.</p>
 *
 * <p><b>Self-report guard:</b> {@link ReportResult#SELF_REPORT} is returned when
 * the buyer's session ID matches the auction's {@code seller_id}.</p>
 */
public class ReportDAO {

    /** Outcome codes returned by {@link #insertReport}. */
    public enum ReportResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** The reporting buyer is the seller of the auction. */
        SELF_REPORT,
        /** The buyer has already reported this auction. */
        ALREADY_REPORTED
    }

    /**
     * Inserts a report against the seller of the given auction.
     *
     * <p>All preconditions (auction existence, self-report, duplicate) are verified
     * within a single transaction so the {@code reportedUserId} read from the DB is
     * always consistent with the insert.</p>
     *
     * @param auctionId   auction being reported (parsed as {@code long} by the servlet)
     * @param reporterId  buyer submitting the report (read from session, never from request)
     * @param description optional sanitized description; {@code null} or blank is stored as NULL
     */
    public ReportResult insertReport(long auctionId, int reporterId, String description) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // Resolve seller_id server-side (IDOR prevention)
            int sellerId;
            String selectSql = "SELECT seller_id FROM auction WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return ReportResult.AUCTION_NOT_FOUND;
                    }
                    sellerId = rs.getInt("seller_id");
                }
            }

            if (sellerId == reporterId) {
                conn.rollback();
                return ReportResult.SELF_REPORT;
            }

            // Friendly duplicate check before hitting the UNIQUE constraint
            String existsSql =
                    "SELECT 1 FROM seller_reports "
                    + "WHERE reporter_user_id = ? AND auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                ps.setInt(1, reporterId);
                ps.setLong(2, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return ReportResult.ALREADY_REPORTED;
                    }
                }
            }

            String insertSql =
                    "INSERT INTO seller_reports "
                    + "(reporter_user_id, reported_user_id, auction_id, description) "
                    + "VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, reporterId);
                ps.setInt(2, sellerId);
                ps.setLong(3, auctionId);
                if (description != null && !description.isBlank()) {
                    ps.setString(4, description);
                } else {
                    ps.setNull(4, Types.VARCHAR);
                }
                ps.executeUpdate();
            }

            // Increment aggregate report_count atomically with the insert
            String incrementSql =
                    "UPDATE auction SET report_count = report_count + 1 WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(incrementSql)) {
                ps.setLong(1, auctionId);
                ps.executeUpdate();
            }

            conn.commit();
            return ReportResult.SUCCESS;

        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) { }
            }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException ignored) { }
            }
        }
    }
}
