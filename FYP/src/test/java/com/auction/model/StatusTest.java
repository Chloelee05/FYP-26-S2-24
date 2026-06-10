package com.auction.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Status enum — account lifecycle IDs")
class StatusTest {

    @Test
    @DisplayName("PENDING and REJECTED match migration user_status rows")
    void pendingAndRejectedIds() {
        assertEquals(4, Status.PENDING.getId());
        assertEquals(5, Status.REJECTED.getId());
        assertEquals(Status.PENDING, Status.getStatus(4));
        assertEquals(Status.REJECTED, Status.getStatus(5));
    }

    @Test
    @DisplayName("existing statuses unchanged")
    void originalStatuses() {
        assertEquals(1, Status.ACTIVE.getId());
        assertEquals(2, Status.SUSPENDED.getId());
        assertEquals(3, Status.DELETED.getId());
    }

    @Test
    @DisplayName("invalid id throws")
    void invalidId() {
        assertThrows(IllegalArgumentException.class, () -> Status.getStatus(99));
    }
}
