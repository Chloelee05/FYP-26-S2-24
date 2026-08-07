package com.auction.servlet.api;

import com.auction.dao.PlatformSettingsDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.test.ApiTestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NEW for the "platform-wide auction rules" admin story: the maximum auction duration guard
 * added to {@code SellerApiServlet#handleCreate} and {@code #handleEdit}, alongside (not in
 * place of) the pre-existing "end date must be after start date" / "must be in the future"
 * checks.
 *
 * <p>Each test stops at the first 400 it reaches. To prove the new guard did not fire when the
 * duration is within bounds, the "accepted" cases deliberately supply an unrelated invalid field
 * further down the same handler (an unparseable quantity) so the request still fails fast with a
 * *different*, pre-existing error — never reaching the DAO — while proving execution passed
 * straight through the new duration check without being rejected by it.</p>
 */
@DisplayName("SellerApiServlet — maximum auction duration guard")
class SellerApiServletAuctionDurationTest {

    private static class Wrapper extends SellerApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException { super.doPost(req, resp); }
    }

    private static final int SELLER_ID = 42;

    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private PlatformSettingsDAO settingsDAO;
    private SellerAuctionDAO auctionDAO;

    @BeforeEach
    void setUp() {
        servlet = new Wrapper();
        settingsDAO = mock(PlatformSettingsDAO.class);
        auctionDAO = mock(SellerAuctionDAO.class);
        servlet.setPlatformSettingsDAO(settingsDAO);
        servlet.setSellerAuctionDAO(auctionDAO);

        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(SELLER_ID));
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create rejects an end date further out than the configured max duration")
    void createRejectsDurationBeyondLimit() throws Exception {
        when(req.getPathInfo()).thenReturn("/create");
        when(settingsDAO.getInt(eq("max_auction_duration_days"), anyInt())).thenReturn(30);

        Instant start = Instant.now();
        Instant end = start.plus(100, ChronoUnit.DAYS); // beyond the 30-day limit
        when(req.getParameter("auctionName")).thenReturn("Item");
        when(req.getParameter("auctionDetails")).thenReturn("Details");
        when(req.getParameter("itemCondition")).thenReturn("1");
        when(req.getParameter("startPrice")).thenReturn("10");
        when(req.getParameter("startDate")).thenReturn(start.toString());
        when(req.getParameter("endDate")).thenReturn(end.toString());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("cannot exceed 30 day(s)"));
        verifyNoInteractions(auctionDAO);
    }

    @Test
    @DisplayName("create does not reject a duration within the configured max, and reaches later validation")
    void createAllowsDurationWithinLimit() throws Exception {
        when(req.getPathInfo()).thenReturn("/create");
        when(settingsDAO.getInt(eq("max_auction_duration_days"), anyInt())).thenReturn(3650);

        Instant start = Instant.now();
        Instant end = start.plus(5, ChronoUnit.DAYS); // well within the limit
        when(req.getParameter("auctionName")).thenReturn("Item");
        when(req.getParameter("auctionDetails")).thenReturn("Details");
        when(req.getParameter("itemCondition")).thenReturn("1");
        when(req.getParameter("startPrice")).thenReturn("10");
        when(req.getParameter("startDate")).thenReturn(start.toString());
        when(req.getParameter("endDate")).thenReturn(end.toString());
        // Unrelated, pre-existing failure further down handleCreate — proves the new guard let
        // this request through rather than proving anything about the eventual create itself.
        when(req.getParameter("quantity")).thenReturn("not-a-number");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        String error = ApiTestSupport.parse(sw).get("error").asText();
        assertFalse(error.contains("cannot exceed"), "duration guard must not have fired");
        assertTrue(error.contains("Quantity must be a whole number"));
    }

    @Test
    @DisplayName("create with no configured setting falls back to the generous default and is not rejected")
    void createFallsBackToDefaultWhenUnconfigured() throws Exception {
        when(req.getPathInfo()).thenReturn("/create");
        // No stub for getInt beyond Mockito's own default (0), so mimic an unconfigured DAO by
        // returning the servlet's own documented fallback, exactly as the real (unmocked)
        // PlatformSettingsDAO would when the key has no row yet.
        when(settingsDAO.getInt(eq("max_auction_duration_days"), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));

        Instant start = Instant.now();
        Instant end = start.plus(30, ChronoUnit.DAYS);
        when(req.getParameter("auctionName")).thenReturn("Item");
        when(req.getParameter("auctionDetails")).thenReturn("Details");
        when(req.getParameter("itemCondition")).thenReturn("1");
        when(req.getParameter("startPrice")).thenReturn("10");
        when(req.getParameter("startDate")).thenReturn(start.toString());
        when(req.getParameter("endDate")).thenReturn(end.toString());
        when(req.getParameter("quantity")).thenReturn("not-a-number");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        String error = ApiTestSupport.parse(sw).get("error").asText();
        assertFalse(error.contains("cannot exceed"), "generous default must not reject a 30-day listing");
    }

    // ── edit ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("edit rejects a new end date further out than the configured max duration from the "
            + "auction's original start")
    void editRejectsDurationBeyondLimit() throws Exception {
        when(req.getPathInfo()).thenReturn("/edit");
        when(settingsDAO.getInt(eq("max_auction_duration_days"), anyInt())).thenReturn(30);

        Instant originalStart = Instant.now().minus(1, ChronoUnit.DAYS);
        when(auctionDAO.getDateCreated(101L, SELLER_ID)).thenReturn(originalStart);

        Instant newEnd = originalStart.plus(100, ChronoUnit.DAYS); // beyond the 30-day limit
        when(req.getParameter("auctionId")).thenReturn("101");
        when(req.getParameter("title")).thenReturn("Item");
        when(req.getParameter("description")).thenReturn("Details");
        when(req.getParameter("endDate")).thenReturn(newEnd.toString());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("cannot exceed 30 day(s)"));
    }

    @Test
    @DisplayName("edit does not reject a new end date within the configured max duration")
    void editAllowsDurationWithinLimit() throws Exception {
        when(req.getPathInfo()).thenReturn("/edit");
        when(settingsDAO.getInt(eq("max_auction_duration_days"), anyInt())).thenReturn(3650);

        Instant originalStart = Instant.now().minus(1, ChronoUnit.DAYS);
        when(auctionDAO.getDateCreated(101L, SELLER_ID)).thenReturn(originalStart);

        Instant newEnd = Instant.now().plus(5, ChronoUnit.DAYS); // well within the limit
        when(req.getParameter("auctionId")).thenReturn("101");
        when(req.getParameter("title")).thenReturn("Item");
        when(req.getParameter("description")).thenReturn("Details");
        when(req.getParameter("endDate")).thenReturn(newEnd.toString());
        // Unrelated, pre-existing failure further down handleEdit's quantity parse.
        when(req.getParameter("quantity")).thenReturn("not-a-number");

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        String error = ApiTestSupport.parse(sw).get("error").asText();
        assertFalse(error.contains("cannot exceed"), "duration guard must not have fired");
        assertTrue(error.contains("Quantity must be a whole number"));
    }

    @Test
    @DisplayName("edit leaves the duration unchecked when the auction lookup fails, deferring to "
            + "editAuction's own ownership check")
    void editSkipsDurationCheckWhenAuctionLookupFails() throws Exception {
        when(req.getPathInfo()).thenReturn("/edit");
        when(auctionDAO.getDateCreated(anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("not found"));

        when(req.getParameter("auctionId")).thenReturn("999");
        when(req.getParameter("title")).thenReturn("Item");
        when(req.getParameter("description")).thenReturn("Details");
        when(req.getParameter("endDate")).thenReturn(
                Instant.now().plus(1, ChronoUnit.DAYS).toString());

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        // Falls through to editAuction (mocked SellerAuctionDAO returns default/null), which is
        // the authoritative ownership check — this test only asserts the duration guard itself
        // did not throw or reject.
        String error = ApiTestSupport.parse(sw).get("error") == null ? "" :
                ApiTestSupport.parse(sw).get("error").asText();
        assertFalse(error.contains("cannot exceed"));
    }
}
