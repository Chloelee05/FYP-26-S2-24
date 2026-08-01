package com.auction.telegram;

import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.TelegramOutboxDAO;
import com.auction.dao.TelegramOutboxDAO.PendingMessage;
import com.auction.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Delivery rules of {@link TelegramOutboxWorker}: what each class of Telegram error does to
 * the queued row and to the user's link.
 */
@DisplayName("TelegramOutboxWorker — draining the queue")
class TelegramOutboxWorkerTest {

    private static final int USER_ID = 7;
    private static final long ROW_ID = 9L;

    private TelegramOutboxDAO outbox;
    private TelegramLinkDAO links;
    private TelegramOutboxWorker worker;

    @BeforeEach
    void setUp() {
        outbox = mock(TelegramOutboxDAO.class);
        links = mock(TelegramLinkDAO.class);
        worker = new TelegramOutboxWorker(outbox, links);
    }

    private static PendingMessage message(int attempts) {
        return new PendingMessage(ROW_ID, USER_ID, "OUTBID", "<b>Camera</b>", attempts);
    }

    /** A configured bot plus a linked chat, i.e. the happy preconditions for a send. */
    private void givenLinkedChat() {
        when(links.findByUserId(USER_ID)).thenReturn(
                new TelegramLinkDAO.LinkInfo("someone", Instant.now(), "ciphertext"));
    }

    private static MockedStatic<TelegramConfig> configured(boolean yes) {
        MockedStatic<TelegramConfig> config = mockStatic(TelegramConfig.class);
        config.when(TelegramConfig::isConfigured).thenReturn(yes);
        return config;
    }

    private static MockedStatic<SecurityUtil> decryptsTo(String chatId) {
        MockedStatic<SecurityUtil> security = mockStatic(SecurityUtil.class);
        security.when(() -> SecurityUtil.decrypt(anyString())).thenReturn(chatId);
        return security;
    }

    /**
     * Results are built through {@link TelegramClient.SendResult}'s own factories rather than
     * hand-assembled, so this test cannot disagree with the client about which error codes are
     * permanent. Both are reachable because the test shares the client's package.
     */
    private static TelegramClient.SendResult failure(int code, String description, int retryAfter) {
        return TelegramClient.SendResult.failure(code, description, retryAfter);
    }

    private static TelegramClient.SendResult success() {
        return TelegramClient.SendResult.success();
    }

    @Nested
    @DisplayName("When no bot is configured")
    class Unconfigured {

        @Test
        @DisplayName("The worker no-ops quietly, exactly as notification email does without SMTP")
        void noOpsWithoutTouchingTheDatabase() {
            try (MockedStatic<TelegramConfig> ignored = configured(false)) {
                assertEquals(0, worker.runOnce());
            }
            verifyNoInteractions(outbox);
            verifyNoInteractions(links);
        }
    }

    @Nested
    @DisplayName("Delivery outcomes")
    class Outcomes {

        @Test
        @DisplayName("A successful send marks the row SENT")
        void successMarksSent() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage("123456", "<b>Camera</b>"))
                        .thenReturn(success());

                assertTrue(worker.deliver(message(0)));
            }
            verify(outbox).markSent(ROW_ID);
            verify(outbox, never()).markFailed(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("403 (user blocked the bot): the link is deactivated and the row SKIPPED, never retried")
        void blockedUserDeactivatesTheLink() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(failure(403, "Forbidden: bot was blocked by the user", 0));

                assertFalse(worker.deliver(message(0)));
            }
            verify(links).unlinkUser(USER_ID);
            verify(outbox).markSkipped(eq(ROW_ID), contains("403"));
            verify(outbox, never()).markFailed(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("400 \"chat not found\" is treated the same way — the chat is gone for good")
        void chatNotFoundDeactivatesTheLink() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(failure(400, "Bad Request: chat not found", 0));

                worker.deliver(message(0));
            }
            verify(links).unlinkUser(USER_ID);
            verify(outbox).markSkipped(eq(ROW_ID), anyString());
        }

        @Test
        @DisplayName("A generic 400 skips the message but must not cost the user their link")
        void genericBadRequestKeepsTheLink() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(failure(400, "Bad Request: message is too long", 0));

                worker.deliver(message(0));
            }
            verify(links, never()).unlinkUser(anyInt());
            verify(outbox).markSkipped(eq(ROW_ID), anyString());
        }

        @Test
        @DisplayName("429 honours retry_after and does not spend an attempt")
        void floodControlDelaysWithoutCountingAnAttempt() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(failure(429, "Too Many Requests: retry after 31", 31));

                assertFalse(worker.deliver(message(2)));
            }
            verify(outbox).delayFor(ROW_ID, 31);
            verify(outbox, never()).markFailed(anyLong(), anyInt(), anyString());
            verify(outbox, never()).markSkipped(anyLong(), anyString());
            verify(links, never()).unlinkUser(anyInt());
        }

        @Test
        @DisplayName("A 5xx is retried on the backoff ladder, carrying the attempts already spent")
        void serverErrorBacksOff() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(failure(502, "Bad Gateway", 0));

                worker.deliver(message(2));
            }
            verify(outbox).markFailed(eq(ROW_ID), eq(2), contains("502"));
            verify(links, never()).unlinkUser(anyInt());
        }

        @Test
        @DisplayName("A transport failure is retried, not skipped")
        void transportFailureBacksOff() {
            givenLinkedChat();
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(failure(0, "Transport failure", 0));

                worker.deliver(message(0));
            }
            verify(outbox).markFailed(eq(ROW_ID), eq(0), anyString());
            verify(outbox, never()).markSkipped(anyLong(), anyString());
        }

        @Test
        @DisplayName("A user who unlinked between enqueue and send is skipped, and nothing is sent")
        void unlinkedUserIsSkipped() {
            when(links.findByUserId(USER_ID)).thenReturn(null);
            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                assertFalse(worker.deliver(message(0)));
                client.verify(() -> TelegramClient.sendMessage(anyString(), anyString()), never());
            }
            verify(outbox).markSkipped(eq(ROW_ID), contains("no active Telegram link"));
        }
    }

    @Nested
    @DisplayName("Batch pacing")
    class Pacing {

        @Test
        @DisplayName("A gap of at least a second per send keeps within Telegram's per-chat limit")
        void pauseSatisfiesThePerChatLimit() {
            assertTrue(TelegramOutboxWorker.PER_SEND_PAUSE_MS >= 1_000,
                    "Telegram allows roughly one message per second to a chat");
        }

        @Test
        @DisplayName("A paced batch still finishes well inside the claim lease")
        void batchFitsWithinTheLease() {
            long batchMillis = TelegramOutboxWorker.BATCH_SIZE * TelegramOutboxWorker.PER_SEND_PAUSE_MS;
            assertTrue(batchMillis < 120_000,
                    "a batch must complete before its rows' lease expires, or they get re-sent");
        }

        @Test
        @DisplayName("Every message in the batch is delivered, paced one after another")
        void drainsTheWholeBatch() {
            givenLinkedChat();
            when(outbox.claimDue(anyInt())).thenReturn(List.of(
                    new PendingMessage(1L, USER_ID, "OUTBID", "a", 0),
                    new PendingMessage(2L, USER_ID, "WON", "b", 0)));

            try (MockedStatic<TelegramConfig> ignored = configured(true);
                 MockedStatic<SecurityUtil> ignored2 = decryptsTo("123456");
                 MockedStatic<TelegramClient> client = mockStatic(TelegramClient.class)) {
                client.when(() -> TelegramClient.sendMessage(anyString(), anyString()))
                        .thenReturn(success());

                assertEquals(2, worker.runOnce());
            }
            verify(outbox).markSent(1L);
            verify(outbox).markSent(2L);
        }

        @Test
        @DisplayName("An unreachable database ends the pass quietly instead of killing the schedule")
        void databaseFailureIsSwallowed() {
            when(outbox.claimDue(anyInt())).thenThrow(new RuntimeException("connection refused"));
            try (MockedStatic<TelegramConfig> ignored = configured(true)) {
                assertEquals(0, worker.runOnce());
            }
        }
    }

    @Nested
    @DisplayName("Classifying a dead chat")
    class DeadChat {

        @Test
        @DisplayName("403 and 400 chat-not-found are dead; other codes are not")
        void classification() {
            assertTrue(TelegramOutboxWorker.chatIsGone(failure(403, "bot was blocked", 0)));
            assertTrue(TelegramOutboxWorker.chatIsGone(failure(400, "Bad Request: chat not found", 0)));
            assertTrue(TelegramOutboxWorker.chatIsGone(failure(400, "Bad Request: CHAT NOT FOUND", 0)));
            assertFalse(TelegramOutboxWorker.chatIsGone(failure(400, "message is too long", 0)));
            assertFalse(TelegramOutboxWorker.chatIsGone(failure(429, "retry after 5", 5)));
            assertFalse(TelegramOutboxWorker.chatIsGone(failure(502, "Bad Gateway", 0)));
            assertFalse(TelegramOutboxWorker.chatIsGone(failure(400, null, 0)));
        }
    }
}
