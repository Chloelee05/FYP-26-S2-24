import static org.junit.jupiter.api.Assertions.*;

import com.auction.model.AuctionStatus;
import com.auction.util.AuctionStateUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

@DisplayName("AuctionStateUtil – SCRUM-36 state helpers")
public class TestAuctionStateUtil {

    // ------------------------------------------------------------------ status-based

    @Nested
    @DisplayName("isScheduled(status)")
    class IsScheduled {
        @Test void pendingIsScheduled()  { assertTrue(AuctionStateUtil.isScheduled(AuctionStatus.PENDING)); }
        @Test void activeIsNotScheduled(){ assertFalse(AuctionStateUtil.isScheduled(AuctionStatus.ACTIVE)); }
        @Test void finishedIsNotScheduled(){ assertFalse(AuctionStateUtil.isScheduled(AuctionStatus.FINISHED)); }
        @Test void cancelledIsNotScheduled(){ assertFalse(AuctionStateUtil.isScheduled(AuctionStatus.CANCELLED)); }
    }

    @Nested
    @DisplayName("isLive(status)")
    class IsLive {
        @Test void activeIsLive()       { assertTrue(AuctionStateUtil.isLive(AuctionStatus.ACTIVE)); }
        @Test void pendingIsNotLive()   { assertFalse(AuctionStateUtil.isLive(AuctionStatus.PENDING)); }
        @Test void finishedIsNotLive()  { assertFalse(AuctionStateUtil.isLive(AuctionStatus.FINISHED)); }
        @Test void cancelledIsNotLive() { assertFalse(AuctionStateUtil.isLive(AuctionStatus.CANCELLED)); }
    }

    @Nested
    @DisplayName("isEnded(status)")
    class IsEnded {
        @Test void finishedIsEnded()    { assertTrue(AuctionStateUtil.isEnded(AuctionStatus.FINISHED)); }
        @Test void cancelledIsEnded()   { assertTrue(AuctionStateUtil.isEnded(AuctionStatus.CANCELLED)); }
        @Test void activeIsNotEnded()   { assertFalse(AuctionStateUtil.isEnded(AuctionStatus.ACTIVE)); }
        @Test void pendingIsNotEnded()  { assertFalse(AuctionStateUtil.isEnded(AuctionStatus.PENDING)); }
    }

    // ------------------------------------------------------------------ time-based (SCRUM-36 boundary times)

    @Nested
    @DisplayName("isScheduledByTime(now, startDate)")
    class IsScheduledByTime {
        @Test
        @DisplayName("now before start → scheduled")
        void nowBeforeStart() {
            Instant now   = Instant.parse("2026-01-01T10:00:00Z");
            Instant start = Instant.parse("2026-01-01T12:00:00Z");
            assertTrue(AuctionStateUtil.isScheduledByTime(now, start));
        }

        @Test
        @DisplayName("now exactly at start boundary → not scheduled (open)")
        void nowAtStart() {
            Instant t = Instant.parse("2026-01-01T12:00:00Z");
            assertFalse(AuctionStateUtil.isScheduledByTime(t, t));
        }

        @Test
        @DisplayName("now after start → not scheduled")
        void nowAfterStart() {
            Instant start = Instant.parse("2026-01-01T08:00:00Z");
            Instant now   = Instant.parse("2026-01-01T10:00:00Z");
            assertFalse(AuctionStateUtil.isScheduledByTime(now, start));
        }
    }

    @Nested
    @DisplayName("isLiveByTime(now, startDate, endDate)")
    class IsLiveByTime {
        private final Instant start = Instant.parse("2026-01-01T08:00:00Z");
        private final Instant end   = Instant.parse("2026-01-01T18:00:00Z");

        @Test
        @DisplayName("now between start and end → live")
        void nowBetween() {
            Instant now = Instant.parse("2026-01-01T12:00:00Z");
            assertTrue(AuctionStateUtil.isLiveByTime(now, start, end));
        }

        @Test
        @DisplayName("now exactly at start → live (inclusive lower bound)")
        void nowAtStart() {
            assertTrue(AuctionStateUtil.isLiveByTime(start, start, end));
        }

        @Test
        @DisplayName("now exactly at end → not live (exclusive upper bound)")
        void nowAtEnd() {
            assertFalse(AuctionStateUtil.isLiveByTime(end, start, end));
        }

        @Test
        @DisplayName("now before start → not live")
        void nowBeforeStart() {
            Instant now = Instant.parse("2026-01-01T06:00:00Z");
            assertFalse(AuctionStateUtil.isLiveByTime(now, start, end));
        }

        @Test
        @DisplayName("now after end → not live")
        void nowAfterEnd() {
            Instant now = Instant.parse("2026-01-01T20:00:00Z");
            assertFalse(AuctionStateUtil.isLiveByTime(now, start, end));
        }
    }

    @Nested
    @DisplayName("isEndedByTime(now, endDate)")
    class IsEndedByTime {
        @Test
        @DisplayName("now after end → ended")
        void nowAfterEnd() {
            Instant end = Instant.parse("2026-01-01T18:00:00Z");
            Instant now = Instant.parse("2026-01-01T20:00:00Z");
            assertTrue(AuctionStateUtil.isEndedByTime(now, end));
        }

        @Test
        @DisplayName("now exactly at end boundary → ended")
        void nowAtEnd() {
            Instant t = Instant.parse("2026-01-01T18:00:00Z");
            assertTrue(AuctionStateUtil.isEndedByTime(t, t));
        }

        @Test
        @DisplayName("now before end → not ended")
        void nowBeforeEnd() {
            Instant end = Instant.parse("2026-01-01T18:00:00Z");
            Instant now = Instant.parse("2026-01-01T12:00:00Z");
            assertFalse(AuctionStateUtil.isEndedByTime(now, end));
        }
    }
}
