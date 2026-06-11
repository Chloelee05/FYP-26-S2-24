package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Generates plain-text admin analytics export reports. */
public class AdminReportDAO {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String generateUserActivityReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("USER ACTIVITY REPORT\n");
        sb.append("Generated: ").append(FMT.format(Instant.now())).append("\n\n");

        try (Connection conn = DBUtil.connectDB()) {
            appendCount(sb, conn, "Total users (non-deleted)",
                    "SELECT COUNT(*) FROM users u JOIN user_status s ON s.id = u.status_id WHERE s.status <> 'Deleted'");
            appendCount(sb, conn, "Active users",
                    "SELECT COUNT(*) FROM users u JOIN user_status s ON s.id = u.status_id WHERE s.status = 'Active'");
            appendCount(sb, conn, "Pending approval",
                    "SELECT COUNT(*) FROM users u JOIN user_status s ON s.id = u.status_id WHERE s.status = 'Pending'");
            appendCount(sb, conn, "Suspended users",
                    "SELECT COUNT(*) FROM users u JOIN user_status s ON s.id = u.status_id WHERE s.status = 'Suspended'");
            appendCount(sb, conn, "Buyers",
                    "SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.role = 'BUYER'");
            appendCount(sb, conn, "Sellers",
                    "SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.role = 'SELLER'");

            sb.append("\n--- Registrations (last 30 days) ---\n");
            String regSql = "SELECT u.username, r.role, u.date_created FROM users u "
                    + "JOIN roles r ON r.id = u.role_id "
                    + "WHERE u.date_created >= now() - interval '30 days' "
                    + "ORDER BY u.date_created DESC LIMIT 50";
            try (PreparedStatement ps = conn.prepareStatement(regSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("  ").append(rs.getString("username"))
                      .append(" (").append(rs.getString("role")).append(") — ");
                    Timestamp created = rs.getTimestamp("date_created");
                    if (created != null) {
                        sb.append(FMT.format(created.toInstant()));
                    }
                    sb.append('\n');
                }
            }

            sb.append("\n--- Bid activity (last 7 days) ---\n");
            appendCount(sb, conn, "Total bids placed",
                    "SELECT COUNT(*) FROM bids WHERE bid_time >= now() - interval '7 days'");
            appendCount(sb, conn, "Unique bidders",
                    "SELECT COUNT(DISTINCT user_id) FROM bids WHERE bid_time >= now() - interval '7 days'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }

    public String generateRevenueReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("REVENUE REPORT\n");
        sb.append("Generated: ").append(FMT.format(Instant.now())).append("\n\n");

        try (Connection conn = DBUtil.connectDB()) {
            appendDecimal(sb, conn, "Platform revenue (completed winning bids)",
                    "SELECT COALESCE(SUM(d.winning_bid), 0) FROM auction_details d WHERE d.winning_bid IS NOT NULL");
            appendDecimal(sb, conn, "Paid orders total",
                    "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE status IN ('PAID','COMPLETED')");
            appendDecimal(sb, conn, "Completed orders total",
                    "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE status = 'COMPLETED'");
            appendCount(sb, conn, "Pending payment orders",
                    "SELECT COUNT(*) FROM orders WHERE status = 'PENDING_PAYMENT'");
            appendCount(sb, conn, "Paid orders",
                    "SELECT COUNT(*) FROM orders WHERE status = 'PAID'");
            appendCount(sb, conn, "Completed orders",
                    "SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'");

            sb.append("\n--- Platform business model revenue ---\n");
            appendDecimal(sb, conn, "Sale commissions (6%)",
                    "SELECT COALESCE(SUM(amount), 0) FROM platform_revenue WHERE revenue_type = 'COMMISSION'");
            appendDecimal(sb, conn, "Featured listing fees",
                    "SELECT COALESCE(SUM(amount), 0) FROM platform_revenue WHERE revenue_type = 'FEATURED_LISTING'");
            appendCount(sb, conn, "Active featured listings",
                    "SELECT COUNT(*) FROM auction WHERE is_featured = TRUE "
                  + "AND (featured_until IS NULL OR featured_until > now())");

            sb.append("\n--- Revenue by period ---\n");
            String[] labels = { "Last 24 hours", "Last 7 days", "Last 30 days", "Last 90 days" };
            String[] intervals = { "1 day", "7 days", "30 days", "90 days" };
            for (int i = 0; i < labels.length; i++) {
                appendDecimal(sb, conn, labels[i],
                        "SELECT COALESCE(SUM(amount), 0) FROM orders "
                      + "WHERE status IN ('PAID','COMPLETED') AND created_at >= now() - interval '"
                      + intervals[i] + "'");
            }

            sb.append("\n--- Top sellers by revenue ---\n");
            String topSql = "SELECT u.username, COALESCE(SUM(d.winning_bid), 0) AS rev "
                    + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
                    + "JOIN users u ON u.id = a.seller_id "
                    + "WHERE d.winning_bid IS NOT NULL "
                    + "GROUP BY u.username ORDER BY rev DESC LIMIT 10";
            try (PreparedStatement ps = conn.prepareStatement(topSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("  ").append(rs.getString("username"))
                      .append(" — $").append(rs.getBigDecimal("rev")).append('\n');
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }

    public String generateModerationReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("MODERATION REPORT\n");
        sb.append("Generated: ").append(FMT.format(Instant.now())).append("\n\n");

        try (Connection conn = DBUtil.connectDB()) {
            appendCount(sb, conn, "Flagged listings",
                    "SELECT COUNT(*) FROM auction WHERE moderation_state = 'flagged'");
            appendCount(sb, conn, "Removed listings",
                    "SELECT COUNT(*) FROM auction WHERE moderation_state = 'removed'");
            appendCount(sb, conn, "Suspended users",
                    "SELECT COUNT(*) FROM users u JOIN user_status s ON s.id = u.status_id WHERE s.status = 'Suspended'");
            appendCount(sb, conn, "Open account reports",
                    "SELECT COUNT(*) FROM account_reports WHERE resolved = FALSE");
            appendCount(sb, conn, "Open listing reports",
                    "SELECT COUNT(*) FROM seller_reports WHERE resolved = FALSE");
            appendCount(sb, conn, "Open support threads",
                    "SELECT COUNT(*) FROM support_threads WHERE status = 'OPEN'");

            sb.append("\n--- Recent account reports ---\n");
            String arSql = "SELECT ar.id, u.username AS reporter, ar.reason, ar.resolved, ar.created_at "
                    + "FROM account_reports ar JOIN users u ON u.id = ar.reporter_id "
                    + "ORDER BY ar.created_at DESC LIMIT 20";
            try (PreparedStatement ps = conn.prepareStatement(arSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("  #").append(rs.getLong("id")).append(" ")
                      .append(rs.getString("reporter")).append(" — ")
                      .append(rs.getString("reason")).append(" [")
                      .append(rs.getBoolean("resolved") ? "resolved" : "open").append("] ");
                    Timestamp created = rs.getTimestamp("created_at");
                    if (created != null) {
                        sb.append(FMT.format(created.toInstant()));
                    }
                    sb.append('\n');
                }
            }

            sb.append("\n--- Recent listing reports ---\n");
            String srSql = "SELECT sr.id, d.title, sr.description, sr.resolved, sr.created_at "
                    + "FROM seller_reports sr "
                    + "JOIN auction_details d ON d.id = sr.auction_id "
                    + "ORDER BY sr.created_at DESC LIMIT 20";
            try (PreparedStatement ps = conn.prepareStatement(srSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("  #").append(rs.getLong("id")).append(" ")
                      .append(rs.getString("title")).append(" — ")
                      .append(rs.getString("description")).append(" [")
                      .append(rs.getBoolean("resolved") ? "resolved" : "open").append("] ");
                    Timestamp created = rs.getTimestamp("created_at");
                    if (created != null) {
                        sb.append(FMT.format(created.toInstant()));
                    }
                    sb.append('\n');
                }
            }

            sb.append("\n--- Recent suspensions ---\n");
            String suspSql = "SELECT u.username, u.last_status_changed_at FROM users u "
                    + "JOIN user_status s ON s.id = u.status_id "
                    + "WHERE s.status = 'Suspended' ORDER BY u.last_status_changed_at DESC LIMIT 20";
            try (PreparedStatement ps = conn.prepareStatement(suspSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("  ").append(rs.getString("username"));
                    Timestamp changed = rs.getTimestamp("last_status_changed_at");
                    if (changed != null) {
                        sb.append(" — ").append(FMT.format(changed.toInstant()));
                    }
                    sb.append('\n');
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }

    private static void appendCount(StringBuilder sb, Connection conn, String label, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            sb.append(label).append(": ").append(rs.next() ? rs.getInt(1) : 0).append('\n');
        }
    }

    private static void appendDecimal(StringBuilder sb, Connection conn, String label, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            sb.append(label).append(": $").append(rs.next() ? rs.getBigDecimal(1) : 0).append('\n');
        }
    }
}
