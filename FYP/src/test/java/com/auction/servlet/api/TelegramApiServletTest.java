package com.auction.servlet.api;

import com.auction.dao.TelegramLinkDAO;
import com.auction.telegram.TelegramConfig;
import com.auction.telegram.TelegramCopy;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.StringWriter;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Account-side Telegram endpoints: status, code minting with its rate limit, and unlink.
 */
@DisplayName("TelegramApiServlet")
class TelegramApiServletTest {

    private static class Wrapper extends TelegramApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private Wrapper servlet;
    private TelegramLinkDAO dao;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private MockedStatic<TelegramConfig> config;
    private MockedStatic<TelegramCopy> copy;

    @BeforeEach
    void setUp() {
        dao = mock(TelegramLinkDAO.class);
        servlet = new Wrapper();
        servlet.setTelegramLinkDAO(dao);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);

        config = mockStatic(TelegramConfig.class);
        config.when(TelegramConfig::isConfigured).thenReturn(true);
        config.when(TelegramConfig::botUsername).thenReturn("AuctionHubAlertsBot");
        config.when(() -> TelegramConfig.deepLink(anyString()))
              .thenAnswer(inv -> "https://t.me/AuctionHubAlertsBot?start=" + inv.getArgument(0));

        copy = mockStatic(TelegramCopy.class);
        copy.when(TelegramCopy::dialogCopy).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void tearDown() {
        config.close();
        copy.close();
    }

    @Test
    @DisplayName("Status requires authentication")
    void statusRequiresAuth() throws Exception {
        when(req.getPathInfo()).thenReturn("/status");
        ApiTestSupport.bindJsonWriter(resp);

        servlet.doGet(req, resp);

        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("link/start requires authentication")
    void linkStartRequiresAuth() throws Exception {
        when(req.getPathInfo()).thenReturn("/link/start");
        ApiTestSupport.bindJsonWriter(resp);

        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("Status reports the linked account and its connection date")
    void statusReportsLink() throws Exception {
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/status");
        when(dao.findByUserId(4)).thenReturn(linkInfo("chloe"));
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.get("linked").asBoolean());
        assertEquals("chloe", body.get("telegramUsername").asText());
        assertTrue(body.get("available").asBoolean());
    }

    @Test
    @DisplayName("Status stays usable with no bot configured, reporting the feature as unavailable")
    void statusFailsSoftWhenUnconfigured() throws Exception {
        config.when(TelegramConfig::isConfigured).thenReturn(false);
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/status");
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertFalse(body.get("available").asBoolean());
        assertFalse(body.get("linked").asBoolean());
        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("link/start is refused with 503 when no bot is configured")
    void linkStartFailsSoftWhenUnconfigured() throws Exception {
        config.when(TelegramConfig::isConfigured).thenReturn(false);
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/link/start");
        ApiTestSupport.bindJsonWriter(resp);

        servlet.doPost(req, resp);

        verify(resp).setStatus(503);
        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("link/start mints a deep link and a 6-digit code that expire together")
    void linkStartMintsBothPaths() throws Exception {
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/link/start");
        when(dao.countCodesMintedSince(eq(4), anyInt())).thenReturn(0);
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

        servlet.doPost(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.get("deepLink").asText().startsWith("https://t.me/AuctionHubAlertsBot?start="));
        assertTrue(body.get("code").asText().matches("\\d{6}"));
        assertEquals("AuctionHubAlertsBot", body.get("botUsername").asText());
        assertEquals(TelegramLinkDAO.CODE_TTL_MINUTES * 60, body.get("expiresInSeconds").asInt());
        verify(dao).mintCodes(eq(4), anyString(), matches("\\d{6}"));
    }

    @Test
    @DisplayName("Too many starts in the window are refused with 429 and mint nothing")
    void linkStartIsRateLimited() throws Exception {
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/link/start");
        when(dao.countCodesMintedSince(4, TelegramApiServlet.START_WINDOW_MINUTES))
                .thenReturn(TelegramApiServlet.MAX_STARTS_PER_WINDOW);
        ApiTestSupport.bindJsonWriter(resp);

        servlet.doPost(req, resp);

        verify(resp).setStatus(429);
        verify(dao, never()).mintCodes(anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("Unlink disconnects the caller's own account")
    void unlink() throws Exception {
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/unlink");
        when(dao.unlinkUser(4)).thenReturn(true);
        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);

        servlet.doPost(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(dao).unlinkUser(4);
        verify(resp).setStatus(200);
        assertFalse(body.get("linked").asBoolean());
    }

    @Test
    @DisplayName("Unlinking when nothing is connected still succeeds")
    void unlinkWhenNotLinked() throws Exception {
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/unlink");
        when(dao.unlinkUser(4)).thenReturn(false);
        ApiTestSupport.bindJsonWriter(resp);

        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("An unknown sub-path is a 404")
    void unknownPath() throws Exception {
        authenticate(4);
        when(req.getPathInfo()).thenReturn("/nope");
        ApiTestSupport.bindJsonWriter(resp);

        servlet.doGet(req, resp);

        verify(resp).setStatus(404);
    }

    private void authenticate(int userId) {
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(userId));
    }

    private static TelegramLinkDAO.LinkInfo linkInfo(String username) {
        return new TelegramLinkDAO.LinkInfo(username, Instant.now(), "cipher");
    }
}
