package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DatabaseBackupUtil – restore validation")
class DatabaseBackupUtilTest {

    @Test
    @DisplayName("restoreSql rejects empty input")
    void rejectEmpty() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseBackupUtil.restoreSql(""));
        assertThrows(IllegalArgumentException.class, () -> DatabaseBackupUtil.restoreSql("   "));
    }

    @Test
    @DisplayName("restoreSql rejects DROP/TRUNCATE/ALTER SYSTEM")
    void rejectDestructive() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseBackupUtil.restoreSql("DROP TABLE users;"));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseBackupUtil.restoreSql("TRUNCATE users;"));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseBackupUtil.restoreSql("ALTER SYSTEM SET foo = bar;"));
    }
}
