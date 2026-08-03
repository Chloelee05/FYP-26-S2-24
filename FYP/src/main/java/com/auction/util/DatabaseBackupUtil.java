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
import java.util.Locale;
import java.util.Map;

/** JDBC-based PostgreSQL backup export and restore for admin database management. */
public final class DatabaseBackupUtil {

    private DatabaseBackupUtil() { }

    public static Map<String, Object> status() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection conn = DBUtil.connectDB()) {
            out.put("database", conn.getCatalog());
            // The JDBC URL is deliberately not returned. It names the internal database
            // host, which an admin has no operational use for and which would be on
            // screen during any demo or screen-share of this page.
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

    /** Outcome of a restore, so the caller can report what was actually applied. */
    public static final class RestoreResult {
        private final int statements;
        private final int rowsInserted;

        RestoreResult(int statements, int rowsInserted) {
            this.statements = statements;
            this.rowsInserted = rowsInserted;
        }

        public int getStatements() { return statements; }

        /** Rows the database actually accepted; {@code ON CONFLICT DO NOTHING} skips duplicates. */
        public int getRowsInserted() { return rowsInserted; }
    }

    public static RestoreResult restoreSql(String sql) throws Exception {
        // Validate the whole file before opening a connection, so a bad backup is
        // refused without a transaction ever being started.
        List<String> inserts = parseInserts(sql);
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                int rows = 0;
                for (String stmt : inserts) {
                    rows += st.executeUpdate(stmt);
                }
                resyncIdentitySequences(conn);
                conn.commit();
                return new RestoreResult(inserts.size(), rows);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Extracts the INSERT statements from a backup file, rejecting anything else.
     *
     * <p>Kept separate from {@link #restoreSql(String)} and free of JDBC so the parser can
     * be tested against real {@link #exportSql()} output rather than against a mock. The
     * shape it has to survive is what {@code exportSql} emits: a leading comment block, a
     * bare {@code BEGIN;}, then a {@code -- Table: x} comment before each table's inserts,
     * and a trailing {@code COMMIT;} — all of which carry the {@code ;} delimiter through
     * from {@link #splitStatements(String)}.</p>
     */
    static List<String> parseInserts(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Backup file is empty.");
        List<String> inserts = new ArrayList<>();
        for (String chunk : splitStatements(sql)) {
            // A comment or an undelimited BEGIN carries no ';', so it arrives glued to the
            // statement that follows it. Strip those leading lines only: a '--' further in
            // may be inside a text value that legitimately contains a newline.
            String stmt = stripLeadingNonStatementLines(chunk);
            if (stmt.isEmpty()) continue;

            String bare = stripTrailingSemicolons(stmt);
            if (bare.isEmpty()) continue;
            if (isTransactionControl(bare)) continue;

            if (!bare.toUpperCase(Locale.ROOT).startsWith("INSERT INTO")) {
                throw new IllegalArgumentException("Only INSERT statements are allowed during restore.");
            }
            // An INSERT that smuggles a second statement after an inline ';' would be run
            // in full by the driver, so the leading keyword alone is not enough.
            if (hasEmbeddedStatementSeparator(bare)) {
                throw new IllegalArgumentException(
                        "A backup statement contains more than one SQL statement.");
            }
            inserts.add(stmt);
        }
        if (inserts.isEmpty()) {
            throw new IllegalArgumentException("Backup file contains no INSERT statements.");
        }
        return inserts;
    }

    private static String stripLeadingNonStatementLines(String chunk) {
        String[] lines = chunk.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("--")
                    || isTransactionControl(stripTrailingSemicolons(line))) {
                i++;
            } else {
                break;
            }
        }
        return String.join("\n", java.util.Arrays.asList(lines).subList(i, lines.length)).trim();
    }

    private static String stripTrailingSemicolons(String s) {
        String out = s.trim();
        while (out.endsWith(";")) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }

    private static boolean isTransactionControl(String bare) {
        return bare.equalsIgnoreCase("BEGIN")
            || bare.equalsIgnoreCase("COMMIT")
            || bare.equalsIgnoreCase("END")
            || bare.equalsIgnoreCase("START TRANSACTION");
    }

    /** True if a ';' appears outside a quoted literal with SQL still following it. */
    private static boolean hasEmbeddedStatementSeparator(String s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                if (inQuote && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;              // '' is an escaped quote, not the end of the literal
                    continue;
                }
                inQuote = !inQuote;
            } else if (c == ';' && !inQuote) {
                return !s.substring(i + 1).trim().isEmpty();
            }
        }
        return false;
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
        // 14 of this schema's tables key off GENERATED ALWAYS identity columns, and
        // PostgreSQL refuses an explicit value for one unless the INSERT says so. Without
        // this clause a backup of users, auction or bids cannot be restored at all.
        String overriding = hasAlwaysIdentity(conn, table) ? " OVERRIDING SYSTEM VALUE" : "";
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
                sb.append(')').append(overriding).append(" VALUES (");
                for (int c = 1; c <= cols; c++) {
                    if (c > 1) sb.append(", ");
                    Object v = rs.getObject(c);
                    sb.append(sqlLiteral(v));
                }
                sb.append(") ON CONFLICT DO NOTHING;\n");
            }
        }
    }

    private static boolean hasAlwaysIdentity(Connection conn, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns "
                   + "WHERE table_schema = 'public' AND table_name = ? "
                   + "AND is_identity = 'YES' AND identity_generation = 'ALWAYS' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Pushes each identity sequence past the highest id now present.
     *
     * <p>A restore writes explicit ids, which leaves the sequence where it was. Restoring
     * into an emptied table therefore hands back a database whose next insert collides on
     * the primary key — so the restore has to put the sequences back itself, or it has
     * only half finished.</p>
     */
    private static void resyncIdentitySequences(Connection conn) throws SQLException {
        String cols = "SELECT table_name, column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND is_identity = 'YES'";
        List<String[]> identities = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(cols)) {
            while (rs.next()) {
                identities.add(new String[] { rs.getString(1), rs.getString(2) });
            }
        }
        try (Statement st = conn.createStatement()) {
            for (String[] id : identities) {
                String qualified = "\"" + id[0] + "\"";
                String column = "\"" + id[1] + "\"";
                // setval is a SELECT, so it must go through execute() rather than executeUpdate().
                // The third argument is false for an empty table, which leaves the next id at 1
                // instead of handing out 2.
                st.execute(
                        "SELECT setval(seq, GREATEST(COALESCE(mx, 0), 1), mx IS NOT NULL) "
                      + "FROM (SELECT pg_get_serial_sequence('" + id[0] + "', '" + id[1] + "') AS seq, "
                      + "(SELECT MAX(" + column + ") FROM " + qualified + ") AS mx) s "
                      + "WHERE seq IS NOT NULL");
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

    private static List<String> splitStatements(String sql) {
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
        } catch (IOException e) {
            throw new IllegalArgumentException("Backup file could not be read.", e);
        }
        return out;
    }
}
