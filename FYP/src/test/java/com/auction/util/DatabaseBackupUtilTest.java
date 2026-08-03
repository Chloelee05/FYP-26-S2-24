package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DatabaseBackupUtil – backup parsing and restore validation")
class DatabaseBackupUtilTest {

    /**
     * Byte-for-byte the shape {@code exportSql()} emits: header comments, a bare
     * {@code BEGIN;}, a {@code -- Table: x} comment before each table's rows, a blank
     * line between tables, and a trailing {@code COMMIT;}.
     *
     * <p>Both restore bugs this class guards against were invisible to a mocked test and
     * only appear when the parser is fed this exact layout, so the fixture is kept
     * literal rather than generated.</p>
     */
    private static final String REAL_BACKUP =
              "-- AuctionHub database backup\n"
            + "-- Generated: 2026-08-04T00:00:00Z\n"
            + "\n"
            + "BEGIN;\n"
            + "\n"
            + "-- Table: roles\n"
            + "INSERT INTO \"roles\" (\"id\", \"role\") VALUES (1, 'Admin') ON CONFLICT DO NOTHING;\n"
            + "INSERT INTO \"roles\" (\"id\", \"role\") VALUES (2, 'Buyer') ON CONFLICT DO NOTHING;\n"
            + "\n"
            + "-- Table: user_status\n"
            + "INSERT INTO \"user_status\" (\"id\", \"status\") VALUES (1, 'Active') ON CONFLICT DO NOTHING;\n"
            + "INSERT INTO \"user_status\" (\"id\", \"status\") VALUES (4, 'Pending') ON CONFLICT DO NOTHING;\n"
            + "\n"
            + "COMMIT;\n";

    @Nested
    @DisplayName("parseInserts over real exportSql() output")
    class RealBackupShape {

        @Test
        @DisplayName("keeps every INSERT, including the first row of each table")
        void keepsEveryInsert() {
            List<String> inserts = DatabaseBackupUtil.parseInserts(REAL_BACKUP);
            // Regression: the '-- Table: x' comment has no ';', so it used to merge into the
            // following INSERT's chunk and that whole chunk was then dropped as a comment.
            // Replayed with 4 INSERTs across 2 tables, only 2 survived.
            assertEquals(4, inserts.size(), "all four INSERTs must survive parsing");
            assertTrue(inserts.get(0).contains("'Admin'"), "first row of the first table is kept");
            assertTrue(inserts.get(2).contains("'Active'"), "first row of the second table is kept");
        }

        @Test
        @DisplayName("strips the -- Table comment off the statement it was glued to")
        void stripsLeadingComment() {
            List<String> inserts = DatabaseBackupUtil.parseInserts(REAL_BACKUP);
            for (String stmt : inserts) {
                assertTrue(stmt.startsWith("INSERT INTO"),
                        "statement handed to the driver must start with INSERT, was: " + stmt);
                assertFalse(stmt.contains("-- Table:"), "table comment must not reach the driver");
            }
        }

        @Test
        @DisplayName("accepts the trailing COMMIT; that carries its semicolon")
        void acceptsDelimitedCommit() {
            // Regression: the guard compared against "COMMIT" while splitStatements hands
            // over "COMMIT;", so every app-generated backup fell through to the
            // INSERT-only check and the whole restore was refused with HTTP 400.
            assertDoesNotThrow(() -> DatabaseBackupUtil.parseInserts(REAL_BACKUP));
        }

        @Test
        @DisplayName("accepts BEGIN/COMMIT with or without the semicolon")
        void acceptsBareAndDelimitedTransactionControl() {
            String bare = "BEGIN\nINSERT INTO \"roles\" (\"id\") VALUES (1);\nCOMMIT\n";
            assertEquals(1, DatabaseBackupUtil.parseInserts(bare).size());
        }
    }

    @Nested
    @DisplayName("rejects what it should")
    class Rejections {

        @Test
        @DisplayName("empty input")
        void rejectEmpty() {
            assertThrows(IllegalArgumentException.class, () -> DatabaseBackupUtil.restoreSql(""));
            assertThrows(IllegalArgumentException.class, () -> DatabaseBackupUtil.restoreSql("   "));
        }

        @Test
        @DisplayName("DROP / TRUNCATE / ALTER SYSTEM as statements")
        void rejectDestructive() {
            assertThrows(IllegalArgumentException.class,
                    () -> DatabaseBackupUtil.restoreSql("DROP TABLE users;"));
            assertThrows(IllegalArgumentException.class,
                    () -> DatabaseBackupUtil.restoreSql("TRUNCATE users;"));
            assertThrows(IllegalArgumentException.class,
                    () -> DatabaseBackupUtil.restoreSql("ALTER SYSTEM SET foo = bar;"));
        }

        @Test
        @DisplayName("a second statement smuggled after an inline semicolon")
        void rejectChainedStatement() {
            assertThrows(IllegalArgumentException.class, () -> DatabaseBackupUtil.parseInserts(
                    "INSERT INTO \"roles\" (\"id\") VALUES (1); DROP TABLE users;\n"));
        }

        @Test
        @DisplayName("a file with no INSERT statements at all")
        void rejectNoInserts() {
            assertThrows(IllegalArgumentException.class,
                    () -> DatabaseBackupUtil.parseInserts("BEGIN;\n-- nothing here\nCOMMIT;\n"));
        }
    }

    @Nested
    @DisplayName("does not reject legitimate row data")
    class FalsePositives {

        @Test
        @DisplayName("a product whose title contains the word Drop")
        void allowsDropInAValue() {
            // Regression: the guard scanned the whole file for the substring "DROP ", so a
            // backup containing a listing named "Drop Shoulder Tee" was refused outright.
            String sql = "BEGIN;\n"
                    + "-- Table: auction_details\n"
                    + "INSERT INTO \"auction_details\" (\"id\", \"title\") "
                    + "VALUES (1, 'Drop Shoulder Tee') ON CONFLICT DO NOTHING;\n"
                    + "COMMIT;\n";
            List<String> inserts = DatabaseBackupUtil.parseInserts(sql);
            assertEquals(1, inserts.size());
            assertTrue(inserts.get(0).contains("Drop Shoulder Tee"));
        }

        @Test
        @DisplayName("a description containing TRUNCATE and a semicolon")
        void allowsSemicolonInAValue() {
            String sql = "INSERT INTO \"auction_details\" (\"id\", \"description\") "
                    + "VALUES (1, 'Ready to truncate; or to drop shoulder') ON CONFLICT DO NOTHING;\n";
            assertEquals(1, DatabaseBackupUtil.parseInserts(sql).size());
        }

        @Test
        @DisplayName("a value containing an escaped quote next to a semicolon")
        void allowsEscapedQuoteThenSemicolon() {
            String sql = "INSERT INTO \"auction_details\" (\"id\", \"title\") "
                    + "VALUES (1, 'Seller''s pick; boxed') ON CONFLICT DO NOTHING;\n";
            assertEquals(1, DatabaseBackupUtil.parseInserts(sql).size());
        }

        @Test
        @DisplayName("a multi-line text value whose own line starts with --")
        void allowsCommentLikeLineInsideAValue() {
            String sql = "-- Table: auction_details\n"
                    + "INSERT INTO \"auction_details\" (\"id\", \"description\") VALUES (1, 'line one\n"
                    + "-- not a comment, part of the description\n"
                    + "line three') ON CONFLICT DO NOTHING;\n";
            List<String> inserts = DatabaseBackupUtil.parseInserts(sql);
            assertEquals(1, inserts.size());
            assertTrue(inserts.get(0).contains("-- not a comment"),
                    "only leading comment lines are stripped");
        }
    }
}
