package com.auction.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC-based PostgreSQL backup export and restore for admin database management. */
public final class DatabaseBackupUtil {

    private DatabaseBackupUtil() { }

    public static Map<String, Object> status() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection conn = DBUtil.connectDB()) {
            out.put("database", conn.getCatalog());
            out.put("url", conn.getMetaData().getURL());
            out.put("tableCount", listTables(conn).size());
            List<Map<String, Object>> tables = new ArrayList<>();
            for (String t : listTables(conn)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", t);
                row.put("rows", countRows(conn, t));
                tables.add(row);
            }
            out.put("tables", tables);
        }
        return out;
    }

    public static byte[] exportSql() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("-- AuctionHub database backup\n");
        sb.append("-- Generated: ").append(Instant.now()).append("\n\n");
        sb.append("BEGIN;\n\n");
        try (Connection conn = DBUtil.connectDB()) {
            for (String table : listTables(conn)) {
                sb.append("-- Table: ").append(table).append('\n');
                exportTable(conn, table, sb);
                sb.append('\n');
            }
        }
        sb.append("COMMIT;\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void restoreSql(String sql) throws Exception {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Backup file is empty.");
        String upper = sql.toUpperCase();
        if (upper.contains("DROP ") || upper.contains("TRUNCATE ") || upper.contains("ALTER SYSTEM")) {
            throw new IllegalArgumentException("Backup file contains disallowed statements.");
        }
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String stmt : splitStatements(sql)) {
                    String s = stmt.trim();
                    if (s.isEmpty() || s.startsWith("--")) continue;
                    if ("BEGIN".equalsIgnoreCase(s) || "COMMIT".equalsIgnoreCase(s)) continue;
                    if (!s.toUpperCase().startsWith("INSERT INTO")) {
                        throw new IllegalArgumentException("Only INSERT statements are allowed during restore.");
                    }
                    st.executeUpdate(s);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static List<String> listTables(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, "public", "%", new String[] { "TABLE" })) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.startsWith("pg_")) tables.add(name);
            }
        }
        tables.sort(String::compareToIgnoreCase);
        return tables;
    }

    private static long countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static void exportTable(Connection conn, String table, StringBuilder sb) throws SQLException {
        String q = "SELECT * FROM \"" + table + "\"";
        try (PreparedStatement ps = conn.prepareStatement(q);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                sb.append("INSERT INTO \"").append(table).append("\" (");
                for (int c = 1; c <= cols; c++) {
                    if (c > 1) sb.append(", ");
                    sb.append('"').append(md.getColumnName(c)).append('"');
                }
                sb.append(") VALUES (");
                for (int c = 1; c <= cols; c++) {
                    if (c > 1) sb.append(", ");
                    Object v = rs.getObject(c);
                    sb.append(sqlLiteral(v));
                }
                sb.append(") ON CONFLICT DO NOTHING;\n");
            }
        }
    }

    private static String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof java.sql.Timestamp) return "'" + v.toString().replace("'", "''") + "'";
        if (v instanceof java.sql.Date) return "'" + v.toString().replace("'", "''") + "'";
        if (v instanceof byte[]) return "NULL /* bytea omitted */";
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private static List<String> splitStatements(String sql) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new StringReader(sql))) {
            String line;
            while ((line = br.readLine()) != null) {
                cur.append(line).append('\n');
                if (line.trim().endsWith(";")) {
                    out.add(cur.toString());
                    cur = new StringBuilder();
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }
}
