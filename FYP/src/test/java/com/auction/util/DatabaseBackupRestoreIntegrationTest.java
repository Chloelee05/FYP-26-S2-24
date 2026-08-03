package com.auction.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips a real backup through a real PostgreSQL database: export, wipe, restore.
 *
 * <p>Every other test in this suite is mocked, which is why three separate restore
 * defects survived 1200 passing tests: the {@code COMMIT;} delimiter mismatch, the
 * {@code -- Table:} comment swallowing the first row of each table, and — invisible to
 * any parser-level test — {@code GENERATED ALWAYS} identity columns rejecting the
 * explicit ids the exporter writes. Only an end-to-end run finds the third.</p>
 *
 * <p>Opt in with {@code AUCTION_DB_IT=true} and point {@code AUCTION_DB_URL} at a
 * throwaway database. It is skipped by default so {@code mvn test} needs no database,
 * and it refuses to run against the hosted database outright — it deletes rows.</p>
 */
@EnabledIfEnvironmentVariable(named = "AUCTION_DB_IT", matches = "true")
@DisplayName("DatabaseBackupUtil – export/restore round trip against real PostgreSQL")
class DatabaseBackupRestoreIntegrationTest {

    /** Mirrors the 14 real tables whose primary key is GENERATED ALWAYS AS IDENTITY. */
    private static final String IDENTITY_TABLE = "backup_it_identity";
    /** Mirrors the tables with a plain declared primary key. */
    private static final String PLAIN_TABLE = "backup_it_plain";

    @BeforeEach
    void seedFixtures() throws Exception {
        try (Connection conn = DBUtil.connectDB()) {
            String url = conn.getMetaData().getURL();
            assertFalse(url.contains("render.com") || url.contains("supabase"),
                    "this test deletes rows and must never point at the hosted database");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS " + IDENTITY_TABLE);
                st.executeUpdate("DROP TABLE IF EXISTS " + PLAIN_TABLE);
                st.executeUpdate("CREATE TABLE " + IDENTITY_TABLE + " ("
                        + "id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                        + "label text NOT NULL)");
                st.executeUpdate("INSERT INTO " + IDENTITY_TABLE + " (label) VALUES "
                        + "('first row of the table'), "
                        + "('Drop Shoulder Tee'), "
                        + "('Seller''s pick; boxed'), "
                        + "('last row')");
                st.executeUpdate("CREATE TABLE " + PLAIN_TABLE
                        + " (id integer PRIMARY KEY, label text NOT NULL)");
                st.executeUpdate("INSERT INTO " + PLAIN_TABLE + " VALUES "
                        + "(1, 'first plain row'), (2, 'second plain row')");
            }
        }
    }

    @Test
    @DisplayName("download-then-restore replaces every row it exported")
    void roundTripLosesNothing() throws Exception {
        String backup = exportAsText();
        assertTrue(backup.contains("-- Table: " + IDENTITY_TABLE), "export writes the table comment");
        assertEquals(4, count(IDENTITY_TABLE));
        assertEquals(2, count(PLAIN_TABLE));

        wipeFixtures();
        assertEquals(0, count(IDENTITY_TABLE), "fixture emptied before the restore");
        assertEquals(0, count(PLAIN_TABLE));

        DatabaseBackupUtil.RestoreResult result = DatabaseBackupUtil.restoreSql(backup);

        assertEquals(4, count(IDENTITY_TABLE),
                "all four rows return, including the first row of the table");
        assertEquals(2, count(PLAIN_TABLE));
        assertTrue(result.getStatements() > 0, "restore reports the statements it applied");
        assertTrue(result.getRowsInserted() > 0, "restore reports the rows it inserted");
    }

    @Test
    @DisplayName("a GENERATED ALWAYS identity column accepts its exported ids")
    void identityColumnsRestore() throws Exception {
        String backup = exportAsText();
        assertTrue(backup.contains("\"" + IDENTITY_TABLE + "\" (\"id\", \"label\") "
                        + "OVERRIDING SYSTEM VALUE VALUES"),
                "an identity table must be exported with OVERRIDING SYSTEM VALUE");
        assertFalse(backup.contains("\"" + PLAIN_TABLE + "\" (\"id\", \"label\") "
                        + "OVERRIDING SYSTEM VALUE"),
                "a table with no identity column must not carry the clause");

        wipeFixtures();
        assertDoesNotThrow(() -> DatabaseBackupUtil.restoreSql(backup));
        assertEquals("Drop Shoulder Tee", labelOf(IDENTITY_TABLE, 2));
        assertEquals("Seller's pick; boxed", labelOf(IDENTITY_TABLE, 3));
    }

    @Test
    @DisplayName("the restored database can still take a new row")
    void identitySequenceIsResynced() throws Exception {
        String backup = exportAsText();
        wipeFixtures();
        DatabaseBackupUtil.restoreSql(backup);

        // Restoring explicit ids leaves the sequence behind them, so without a resync the
        // next insert collides on the primary key and the "restored" database is unusable.
        try (Connection conn = DBUtil.connectDB(); Statement st = conn.createStatement()) {
            assertDoesNotThrow(() -> st.executeUpdate(
                    "INSERT INTO " + IDENTITY_TABLE + " (label) VALUES ('inserted after restore')"));
        }
        assertEquals(5, count(IDENTITY_TABLE));
    }

    @Test
    @DisplayName("restoring onto a populated database inserts nothing and says so")
    void idempotentRestore() throws Exception {
        DatabaseBackupUtil.RestoreResult result = DatabaseBackupUtil.restoreSql(exportAsText());

        assertEquals(4, count(IDENTITY_TABLE), "no duplicates: ON CONFLICT DO NOTHING holds");
        assertEquals(0, result.getRowsInserted(),
                "an honest count of zero, rather than a success message over nothing");
    }

    @Test
    @DisplayName("status() does not disclose the database host")
    void statusHidesJdbcUrl() throws Exception {
        assertFalse(DatabaseBackupUtil.status().containsKey("url"));
    }

    private String exportAsText() throws Exception {
        return new String(DatabaseBackupUtil.exportSql(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private int count(String table) throws Exception {
        try (Connection conn = DBUtil.connectDB();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String labelOf(String table, int id) throws Exception {
        try (Connection conn = DBUtil.connectDB();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT label FROM " + table + " WHERE id = " + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void wipeFixtures() throws Exception {
        try (Connection conn = DBUtil.connectDB(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM " + IDENTITY_TABLE);
            st.executeUpdate("DELETE FROM " + PLAIN_TABLE);
        }
    }
}
