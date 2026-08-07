package com.auction.dao;

import com.auction.model.AccountReport;
import com.auction.util.DBUtil;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for abuse reports. Two separate tables sit behind this class:
 * {@code seller_reports} holds a buyer's report about a specific listing and its seller, and
 * {@code account_reports} holds a user-against-user report with no listing attached. Both are
 * joined to {@code users} for display names, and {@code seller_reports} also bumps
 * {@code auction.report_count}. Called by the report submission API and the admin moderation view.
 *
 * <p>The two tables have independent id sequences, so an id alone never identifies a report.
 * Every admin operation carries a {@code type} discriminator ("listing" or "account") to pick
 * the table.</p>
 *
 * <p>One report per auction per buyer, enforced by the
 * {@code UNIQUE (reporter_user_id, auction_id)} constraint on {@code seller_reports}.
 * A pre-check is done first so the servlet receives a clean {@link ReportResult}
 * rather than a raw constraint-violation exception.</p>
 *
 * <p>IDOR prevention: {@code reportedUserId} (the seller) is resolved from the database
 * inside the transaction and never taken from the request.</p>
 *
 * <p>Self-report guard: {@link ReportResult#SELF_REPORT} is returned when
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

            // Friendly duplicate check before hitting the UNIQUE constraint. The constraint is
            // still the real guarantee; this only turns the common case into a readable result.
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

            // Increment aggregate report_count atomically with the insert. This counter is what the
            // moderation queue sorts on, so it must never drift from the number of report rows.
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

    /**
     * Files an account-level report, one user against another, with no listing involved.
     * Unlike {@link #insertReport} there is no uniqueness rule here, since the same person can be
     * reported more than once for different incidents.
     */
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

    /**
     * Every account report as model objects. {@link #getAllReportsUnified} is what the current
     * admin view uses; this one stays for callers that want typed rows from a single table.
     */
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

        // Account reports (user against user). users is joined twice under different aliases,
        // once to name the person who complained and once to name the person complained about.
        String accountSql = "SELECT ar.id, ar.reporter_id, ar.target_id, ar.reason, ar.comment, "
                + "ar.created_at, ar.resolved, ar.admin_reply, "
                + "ru.username AS reporter_name, tu.username AS target_name "
                + "FROM account_reports ar "
                + "JOIN users ru ON ru.id = ar.reporter_id "
                + "JOIN users tu ON tu.id = ar.target_id";
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
                m.put("admin_reply", rs.getString("admin_reply"));
                m.put("reporter_name", rs.getString("reporter_name"));
                m.put("target_name", rs.getString("target_name"));
                result.add(m);
            }
        }

        // Listing reports (a buyer against a seller's auction). Wrapped defensively so the
        // admin view still loads if the seller_reports migration has not been applied.
        // auction_details is a LEFT JOIN because the reported listing may since have been removed,
        // and the report itself must still appear in the queue with a null title.
        String listingSql = "SELECT sr.id, sr.reporter_user_id, sr.reported_user_id, sr.auction_id, "
                + "sr.description, sr.created_at, sr.resolved, sr.admin_reply, ad.title, "
                + "ru.username AS reporter_name, tu.username AS target_name "
                + "FROM seller_reports sr "
                + "LEFT JOIN auction_details ad ON ad.id = sr.auction_id "
                + "JOIN users ru ON ru.id = sr.reporter_user_id "
                + "JOIN users tu ON tu.id = sr.reported_user_id";
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
                m.put("admin_reply", rs.getString("admin_reply"));
                m.put("reporter_name", rs.getString("reporter_name"));
                m.put("target_name", rs.getString("target_name"));
                m.put("auction_id", rs.getLong("auction_id"));
                result.add(m);
            }
        } catch (SQLException ignored) {
            // seller_reports table missing, so the account reports loaded above are returned alone
            // rather than failing the whole page.
        }

        // Merged in Java rather than by SQL UNION, because the two tables have different columns.
        // Sorting on the ISO-8601 timestamp string works because that format sorts
        // lexicographically in the same order as chronologically. Nulls sink to the bottom.
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

    /**
     * Returns the reports submitted by the given user (account + listing reports),
     * including resolution status and any admin reply, newest first. Same row shape
     * as {@link #getAllReportsUnified} so the frontend can share rendering logic.
     */
    public List<java.util.Map<String, Object>> listForReporter(int reporterId) throws Exception {
        List<java.util.Map<String, Object>> result = new ArrayList<>();

        String accountSql = "SELECT ar.id, ar.target_id, ar.reason, ar.comment, "
                + "ar.created_at, ar.resolved, ar.admin_reply, tu.username AS target_name "
                + "FROM account_reports ar "
                + "JOIN users tu ON tu.id = ar.target_id "
                + "WHERE ar.reporter_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(accountSql)) {
            stmt.setInt(1, reporterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("type", "account");
                    m.put("reason", rs.getString("reason"));
                    m.put("comment", rs.getString("comment"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    m.put("created_at", ts != null ? ts.toInstant().toString() : null);
                    m.put("resolved", rs.getBoolean("resolved"));
                    m.put("admin_reply", rs.getString("admin_reply"));
                    m.put("target_name", rs.getString("target_name"));
                    result.add(m);
                }
            }
        }

        String listingSql = "SELECT sr.id, sr.auction_id, sr.description, sr.created_at, "
                + "sr.resolved, sr.admin_reply, ad.title, tu.username AS target_name "
                + "FROM seller_reports sr "
                + "LEFT JOIN auction_details ad ON ad.id = sr.auction_id "
                + "JOIN users tu ON tu.id = sr.reported_user_id "
                + "WHERE sr.reporter_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(listingSql)) {
            stmt.setInt(1, reporterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String title = rs.getString("title");
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("type", "listing");
                    m.put("reason", "Listing report" + (title != null ? ": " + title : ""));
                    m.put("comment", rs.getString("description"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    m.put("created_at", ts != null ? ts.toInstant().toString() : null);
                    m.put("resolved", rs.getBoolean("resolved"));
                    m.put("admin_reply", rs.getString("admin_reply"));
                    m.put("target_name", rs.getString("target_name"));
                    m.put("auction_id", rs.getLong("auction_id"));
                    result.add(m);
                }
            }
        } catch (SQLException ignored) {
            // seller_reports table missing, so only the account reports above are returned.
        }

        // Same string-timestamp ordering as getAllReportsUnified, newest first.
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

    /**
     * Writes an admin reply onto the report identified by {@code id} in the table named by
     * {@code type} ({@code "listing"} or {@code "account"}).
     *
     * <p>{@code seller_reports} and {@code account_reports} have independent id sequences, so
     * the same id routinely exists in both and means two unrelated reports. An unrecognised or
 * missing {@code type} therefore fails outright. The previous fallback of trying one
 * table and then the other could attach a reply to whichever report happened to share the
 * number.</p>
     *
     * @return {@code true} if a row was updated; {@code false} if the type was not recognised
     *         or no report with that id exists in the matching table
     */
    public boolean replyToReport(long id, String type, String replyText) throws Exception {
        String table = reportTable(type);
        if (table == null) {
            return false;
        }
        return updateAdminReply(table, id, replyText);
    }

    /**
     * Maps the {@code type} discriminator carried by {@link #getAllReportsUnified} rows to its
     * table, or {@code null} when the caller supplied neither known value.
     */
    public static String reportTable(String type) {
        if ("listing".equalsIgnoreCase(type)) return "seller_reports";
        if ("account".equalsIgnoreCase(type)) return "account_reports";
        return null;
    }

    /**
     * Writes the reply into the given table. The table name is concatenated into the SQL, which is
     * safe only because it comes from {@link #reportTable} and can be one of two fixed literals.
     */
    private boolean updateAdminReply(String table, long id, String replyText) throws Exception {
        String sql = "UPDATE " + table + " SET admin_reply = ? WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, replyText);
            stmt.setLong(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Marks an account report resolved or unresolved. {@code status} arrives as the raw request
     * string, and anything other than "true" is read as false.
     */
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

    /**
     * Loads one account report by id. Note that a missing id yields an AccountReport with all
     * fields left at their defaults rather than null, so callers should check the id field.
     */
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
