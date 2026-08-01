package com.auction.servlet.api;

import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.telegram.TelegramAttemptLimiter;
import com.auction.telegram.TelegramClient;
import com.auction.telegram.TelegramConfig;
import com.auction.telegram.TelegramCopy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The bot's inbound endpoint: secret-token authentication, the two linking paths, and the
 * per-chat guard on guessed codes.
 */
@DisplayName("TelegramWebhookServlet")
class TelegramWebhookServletTest {

    private static final String SECRET = "correct-webhook-secret";
    private static final String CHAT_ID = "55501";

    private static class Wrapper extends TelegramWebhookServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private Wrapper servlet;
    private TelegramLinkDAO linkDAO;
    private UserDAO userDAO;
    private TelegramAttemptLimiter limiter;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    private MockedStatic<TelegramConfig> config;
    private MockedStatic<TelegramClient> client;
    private MockedStatic<TelegramCopy> copy;

    @BeforeEach
    void setUp() {
        linkDAO = mock(TelegramLinkDAO.class);
        userDAO = mock(UserDAO.class);
        limiter = new TelegramAttemptLimiter();

        servlet = new Wrapper();
        servlet.setTelegramLinkDAO(linkDAO);
        servlet.setUserDAO(userDAO);
        servlet.setAttemptLimiter(limiter);

        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);

        config = mockStatic(TelegramConfig.class);
        config.when(TelegramConfig::isConfigured).thenReturn(true);
        config.when(TelegramConfig::webhookSecret).thenReturn(SECRET);
        config.when(TelegramConfig::pepper).thenReturn("unit-test-pepper");

        // Never reach the network, and keep the built-in wording out of the database.
        client = mockStatic(TelegramClient.class);
        client.when(() -> TelegramClient.sendMessage(anyString(), anyString())).thenReturn(null);
        client.when(() -> TelegramClient.escapeHtml(anyString()))
              .thenAnswer(inv -> inv.getArgument(0));

        copy = mockStatic(TelegramCopy.class);
        copy.when(() -> TelegramCopy.get(anyString(), anyString()))
            .thenAnswer(inv -> inv.getArgument(1));
        copy.when(TelegramCopy::dialogCopy).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void tearDown() {
        config.close();
        client.close();
        copy.close();
    }

    private void givenUpdate(String text) throws Exception {
        String json = "{\"message\":{\"chat\":{\"id\":" + CHAT_ID + "},"
                + "\"from\":{\"username\":\"chloe\"},"
                + "\"text\":\"" + text + "\"}}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    }

    private void givenGoodSecret() {
        when(req.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn(SECRET);
    }

    // ── Authentication ────────────────────────────────────────────────────────

    @Test
    @DisplayName("A wrong secret token is rejected with 401 and the body is never read")
    void wrongSecretIsRejected() throws Exception {
        when(req.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn("attacker-guess");

        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
        verify(req, never()).getReader();
        verifyNoInteractions(linkDAO);
    }

    @Test
    @DisplayName("A missing secret token is rejected with 401")
    void missingSecretIsRejected() throws Exception {
        when(req.getHeader("X-Telegram-Bot-Api-Secret-Token")).thenReturn(null);

        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
        verify(req, never()).getReader();
    }

    @Test
    @DisplayName("A secret that is a prefix of the real one is still rejected")
    void prefixOfSecretIsRejected() throws Exception {
        when(req.getHeader("X-Telegram-Bot-Api-Secret-Token"))
                .thenReturn(SECRET.substring(0, SECRET.length() - 1));

        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("Without a configured bot the endpoint refuses rather than accepting traffic")
    void unconfiguredServerRefuses() throws Exception {
        config.when(TelegramConfig::isConfigured).thenReturn(false);
        givenGoodSecret();

        servlet.doPost(req, resp);

        verify(resp).setStatus(503);
        verifyNoInteractions(linkDAO);
    }

    // ── Linking ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A /start deep-link token links the account")
    void deepLinkTokenLinks() throws Exception {
        givenGoodSecret();
        givenUpdate("/start Zm9vYmFyLXRva2Vu");
        when(linkDAO.consumeCode("Zm9vYmFyLXRva2Vu")).thenReturn(9);
        when(linkDAO.link(9, CHAT_ID, "chloe"))
                .thenReturn(new TelegramLinkDAO.LinkOutcome(TelegramLinkDAO.LinkStatus.LINKED, null));
        when(userDAO.getUserById(9)).thenReturn(userNamed("chloelee"));

        servlet.doPost(req, resp);

        verify(linkDAO).consumeCode("Zm9vYmFyLXRva2Vu");
        verify(linkDAO).link(9, CHAT_ID, "chloe");
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("A bare 6-digit message links through exactly the same path")
    void otpLinksThroughTheSamePath() throws Exception {
        givenGoodSecret();
        givenUpdate("123456");
        when(linkDAO.consumeCode("123456")).thenReturn(9);
        when(linkDAO.link(9, CHAT_ID, "chloe"))
                .thenReturn(new TelegramLinkDAO.LinkOutcome(TelegramLinkDAO.LinkStatus.LINKED, null));
        when(userDAO.getUserById(9)).thenReturn(userNamed("chloelee"));

        servlet.doPost(req, resp);

        verify(linkDAO).consumeCode("123456");
        verify(linkDAO).link(9, CHAT_ID, "chloe");
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("The reply identifies the account by masked name, never by email")
    void successReplyMasksTheAccount() throws Exception {
        givenGoodSecret();
        givenUpdate("123456");
        when(linkDAO.consumeCode("123456")).thenReturn(9);
        when(linkDAO.link(anyInt(), anyString(), any()))
                .thenReturn(new TelegramLinkDAO.LinkOutcome(TelegramLinkDAO.LinkStatus.LINKED, null));
        User user = userNamed("chloelee");
        user.setEmail("chloe@example.com");
        when(userDAO.getUserById(9)).thenReturn(user);

        servlet.doPost(req, resp);

        client.verify(() -> TelegramClient.sendMessage(eq(CHAT_ID),
                argThat(body -> body.contains("c***e") && !body.contains("chloe@example.com"))));
    }

    @Test
    @DisplayName("An expired or already-used code is refused and counted against the chat")
    void invalidCodeIsCounted() throws Exception {
        givenGoodSecret();
        givenUpdate("000000");
        when(linkDAO.consumeCode("000000")).thenReturn(null);

        servlet.doPost(req, resp);

        verify(linkDAO, never()).link(anyInt(), anyString(), any());
        verify(resp).setStatus(200);
        assertFalse(limiter.isBlocked(TelegramLinkDAO.hash(CHAT_ID)),
                "one failure is not enough to block");
    }

    @Test
    @DisplayName("Repeated wrong codes block the chat and stop reaching the database")
    void bruteForceIsBlocked() throws Exception {
        givenGoodSecret();
        when(linkDAO.consumeCode(anyString())).thenReturn(null);

        for (int i = 0; i < TelegramAttemptLimiter.MAX_FAILURES; i++) {
            givenUpdate("00000" + i);
            servlet.doPost(req, resp);
        }
        assertTrue(limiter.isBlocked(TelegramLinkDAO.hash(CHAT_ID)));

        clearInvocations(linkDAO);
        givenUpdate("123456");
        servlet.doPost(req, resp);

        verify(linkDAO, never()).consumeCode(anyString());
        verify(resp, atLeastOnce()).setStatus(200);
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("/unlink deactivates the chat's link")
    void unlinkCommand() throws Exception {
        givenGoodSecret();
        givenUpdate("/unlink");
        when(linkDAO.unlinkChat(CHAT_ID)).thenReturn(true);

        servlet.doPost(req, resp);

        verify(linkDAO).unlinkChat(CHAT_ID);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("/start without a payload explains how to connect instead of failing")
    void startWithoutPayload() throws Exception {
        givenGoodSecret();
        givenUpdate("/start");

        servlet.doPost(req, resp);

        verify(linkDAO, never()).consumeCode(anyString());
        client.verify(() -> TelegramClient.sendMessage(eq(CHAT_ID), anyString()));
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("/status reports the linked account")
    void statusCommand() throws Exception {
        givenGoodSecret();
        givenUpdate("/status");
        when(linkDAO.findUserIdByChatId(CHAT_ID)).thenReturn(9);
        when(userDAO.getUserById(9)).thenReturn(userNamed("chloelee"));

        servlet.doPost(req, resp);

        verify(linkDAO).findUserIdByChatId(CHAT_ID);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("Unrecognised chatter gets help rather than an error")
    void unknownTextGetsHelp() throws Exception {
        givenGoodSecret();
        givenUpdate("hello there");

        servlet.doPost(req, resp);

        verify(linkDAO, never()).consumeCode(anyString());
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("An update with no chat is acknowledged, not retried")
    void updateWithoutChatIsAcknowledged() throws Exception {
        givenGoodSecret();
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"update_id\":1}")));

        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verifyNoInteractions(linkDAO);
    }

    @Test
    @DisplayName("A database failure still answers 200 so Telegram does not hammer us")
    void databaseFailureStillAcknowledges() throws Exception {
        givenGoodSecret();
        givenUpdate("123456");
        when(linkDAO.consumeCode(anyString())).thenThrow(new RuntimeException("db down"));

        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("Re-linking warns the chat that just lost the account")
    void relinkWarnsThePreviousChat() throws Exception {
        givenGoodSecret();
        givenUpdate("123456");
        when(linkDAO.consumeCode("123456")).thenReturn(9);
        String displaced = com.auction.util.SecurityUtil.encrypt("99900");
        when(linkDAO.link(9, CHAT_ID, "chloe"))
                .thenReturn(new TelegramLinkDAO.LinkOutcome(TelegramLinkDAO.LinkStatus.LINKED, displaced));
        when(userDAO.getUserById(9)).thenReturn(userNamed("chloelee"));

        servlet.doPost(req, resp);

        client.verify(() -> TelegramClient.sendMessage(eq("99900"), anyString()));
    }

    private static User userNamed(String username) {
        User user = new User();
        user.setId(9);
        user.setUsername(username);
        return user;
    }
}
