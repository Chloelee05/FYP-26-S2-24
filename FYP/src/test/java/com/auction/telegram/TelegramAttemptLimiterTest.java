package com.auction.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-chat guard that makes guessing a 6-digit linking code impractical.
 */
@DisplayName("TelegramAttemptLimiter — per-chat brute-force guard")
class TelegramAttemptLimiterTest {

    @Test
    @DisplayName("Blocks a chat once it burns through its allowance")
    void blocksAfterMaxFailures() {
        TelegramAttemptLimiter limiter = new TelegramAttemptLimiter();
        String chat = "chat-hash-a";

        for (int i = 1; i < TelegramAttemptLimiter.MAX_FAILURES; i++) {
            assertFalse(limiter.recordFailure(chat), "failure " + i + " should still be allowed");
            assertFalse(limiter.isBlocked(chat));
        }

        assertTrue(limiter.recordFailure(chat), "the fifth failure triggers the block");
        assertTrue(limiter.isBlocked(chat));
        assertTrue(limiter.blockedSecondsRemaining(chat) > 0);
    }

    @Test
    @DisplayName("Counts each chat separately, so one attacker cannot lock everyone out")
    void countsPerChat() {
        TelegramAttemptLimiter limiter = new TelegramAttemptLimiter();
        for (int i = 0; i < TelegramAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure("noisy-chat");
        }
        assertTrue(limiter.isBlocked("noisy-chat"));
        assertFalse(limiter.isBlocked("quiet-chat"));
    }

    @Test
    @DisplayName("A correct code clears the chat's history")
    void successResetsTheCounter() {
        TelegramAttemptLimiter limiter = new TelegramAttemptLimiter();
        String chat = "chat-hash-b";
        limiter.recordFailure(chat);
        limiter.recordFailure(chat);
        limiter.recordSuccess(chat);

        for (int i = 1; i < TelegramAttemptLimiter.MAX_FAILURES; i++) {
            assertFalse(limiter.recordFailure(chat), "counting must restart from zero");
        }
    }

    @Test
    @DisplayName("An unknown chat is never blocked and null is tolerated")
    void unknownChatIsFree() {
        TelegramAttemptLimiter limiter = new TelegramAttemptLimiter();
        assertFalse(limiter.isBlocked("never-seen"));
        assertFalse(limiter.isBlocked(null));
        assertFalse(limiter.recordFailure(null));
        assertEquals(0, limiter.blockedSecondsRemaining("never-seen"));
    }
}
