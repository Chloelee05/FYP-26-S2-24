package com.auction.dao;

import com.auction.model.AccountReport;
import com.auction.util.DBUtil;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    public boolean reportUser(AccountReport accountReport)throws Exception
    {
        String sqlString = "INSERT INTO account_reports (reporter_id, target_id, reason, comment, created_at) VALUES(? ,? , ?, ?, ?)";
        try(Connection conn = DBUtil.connectDB();
        PreparedStatement stmt = conn.prepareStatement(sqlString))
        {
            stmt.setLong(1, accountReport.getReporter_id()); //reporter id
            stmt.setLong(2, accountReport.getTarget_id()); //target id
            stmt.setString(3, accountReport.getReason()); //reason
            stmt.setString(4, accountReport.getComment()); //comment
            stmt.setTimestamp(5, Timestamp.from(accountReport.getCreated_at())); //date_time
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            throw new Exception("Failed to report user", e);
        }
    }

    public List<AccountReport> getAllReports() throws Exception
    {
        String sqlString = "SELECT * FROM account_reports";
        List<AccountReport> result = new ArrayList<>();
        try(Connection conn = DBUtil.connectDB();
            PreparedStatement stmt = conn.prepareStatement(sqlString)){
            ResultSet rs = stmt.executeQuery();
            while(rs.next())
            {
                AccountReport accountReport = new AccountReport();
                accountReport.setId(rs.getLong("id"));
                accountReport.setReporter_id(rs.getLong("reporter_id"));
                accountReport.setTarget_id(rs.getLong("target_id"));
                accountReport.setReason(rs.getString("reason"));
                accountReport.setComment(rs.getString("comment"));
                Timestamp ts = rs.getTimestamp("created_at");
                accountReport.setCreated_at(ts != null ? ts.toInstant() : Instant.now());
                accountReport.setResolved(rs.getBoolean("resolved"));
                result.add(accountReport);
            }
        }
        return result;
    }

    /**
     * Returns all reports for the admin moderation view, combining account-level
     * reports ({@code account_reports}, raised against a user) and listing reports
     * ({@code seller_reports}, raised against an auction's seller). Each row carries a
     * {@code type} discriminator ("account" or "listing") so the admin UI can act on
     * the correct table. Newest first.
     */
    public List<java.util.Map<String, Object>> getAllReportsUnified() throws Exception {
        List<java.util.Map<String, Object>> result = new ArrayList<>();

        // Account reports (user-vs-user)
        String accountSql = "SELECT id, reporter_id, target_id, reason, comment, created_at, resolved "
                + "FROM account_reports";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(accountSql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("type", "account");
                m.put("reporter_id", rs.getLong("reporter_id"));
                m.put("target_id", rs.getLong("target_id"));
                m.put("reason", rs.getString("reason"));
                m.put("comment", rs.getString("comment"));
                Timestamp ts = rs.getTimestamp("created_at");
                m.put("created_at", ts != null ? ts.toInstant().toString() : null);
                m.put("resolved", rs.getBoolean("resolved"));
                result.add(m);
            }
        }

        // Listing reports (buyer-vs-seller's auction). Wrapped defensively so the
        // admin view still loads if the seller_reports migration has not been applied.
        String listingSql = "SELECT sr.id, sr.reporter_user_id, sr.reported_user_id, sr.auction_id, "
                + "sr.description, sr.created_at, sr.resolved, ad.title "
                + "FROM seller_reports sr "
                + "LEFT JOIN auction_details ad ON ad.id = sr.auction_id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(listingSql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String title = rs.getString("title");
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("type", "listing");
                m.put("reporter_id", rs.getLong("reporter_user_id"));
                m.put("target_id", rs.getLong("reported_user_id"));
                m.put("reason", "Listing report" + (title != null ? ": " + title : ""));
                m.put("comment", rs.getString("description"));
                Timestamp ts = rs.getTimestamp("created_at");
                m.put("created_at", ts != null ? ts.toInstant().toString() : null);
                m.put("resolved", rs.getBoolean("resolved"));
                result.add(m);
            }
        } catch (SQLException ignored) {
            // seller_reports table missing — account reports already loaded above.
        }

        // Newest first across both sources (nulls last).
        result.sort((a, b) -> {
            String ca = (String) a.get("created_at");
            String cb = (String) b.get("created_at");
            if (ca == null && cb == null) return 0;
            if (ca == null) return 1;
            if (cb == null) return -1;
            return cb.compareTo(ca);
        });
        return result;
    }

    /** Updates the {@code resolved} flag on a listing report ({@code seller_reports}). */
    public boolean setSellerReportStatus(Long id, boolean resolved) throws Exception {
        String sqlString = "UPDATE seller_reports SET resolved = ? WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(sqlString)) {
            stmt.setBoolean(1, resolved);
            stmt.setLong(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    public boolean setReportStatus(Long id, String status) throws Exception{
        String sqlString = "UPDATE account_reports SET resolved = ? WHERE id = ?";
        try(Connection conn = DBUtil.connectDB();
            PreparedStatement stmt = conn.prepareStatement(sqlString)){
            boolean value = status.equalsIgnoreCase("true");
            stmt.setBoolean(1, value);
            stmt.setLong(2, id);
            int result = stmt.executeUpdate();
            return result > 0;
        }
    }

    public AccountReport findById(Long report_id) throws Exception{
        String sqlString = "SELECT * FROM account_reports WHERE id = ?";
        try(Connection conn = DBUtil.connectDB();
            PreparedStatement stmt = conn.prepareStatement(sqlString)) {
            stmt.setLong(1, report_id);
            try(ResultSet rs = stmt.executeQuery())
            {
                AccountReport accountReport = new AccountReport();
                while (rs.next())
                {
                    accountReport.setId(rs.getLong("id"));
                    accountReport.setReporter_id(rs.getLong("reporter_id"));
                    accountReport.setTarget_id(rs.getLong("target_id"));
                    accountReport.setReason(rs.getString("reason"));
                    accountReport.setComment(rs.getString("comment"));
                    accountReport.setCreated_at(rs.getTimestamp("created_at").toInstant());
                    accountReport.setResolved(rs.getBoolean("resolved"));
                }
                return accountReport;
            }
        }
    }
}
