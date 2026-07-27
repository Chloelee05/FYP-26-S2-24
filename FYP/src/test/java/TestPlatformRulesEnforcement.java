import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.dao.PlatformRulesDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionType;
import com.auction.model.admin.PlatformRules;
import com.auction.servlet.PlaceBidServlet;
import com.auction.servlet.api.BidApiServlet;
import com.auction.servlet.api.SellerApiServlet;
import com.auction.test.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for platform-rule enforcement (SCRUM-67 / SCRUM-69).
 *
 * <p>Covers the maximum-auction-duration check on listing creation and rescheduling,
 * the minimum-increment rejection surfaced to buyers, and the proxy-bidding step
 * raised to the platform minimum.</p>
 */
@DisplayName("Platform auction rules — enforcement")
class TestPlatformRulesEnforcement {

    private static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    /** Exposes the protected doGet/doPost of the seller API for direct invocation. */
    private static class SellerWrapper extends SellerApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    /** Exposes the protected doPost of the bid API for direct invocation. */
    private static class BidWrapper extends BidApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    @Nested
    @DisplayName("maximum auction duration — POST /api/seller/create")
    class CreateAuction {

        private PlatformRulesDAO mockRulesDAO;
        private SellerAuctionDAO mockAuctionDAO;
        private SellerWrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;
        private final Instant start = Instant.parse("2026-08-01T00:00:00Z");

        @BeforeEach
        void setUp() {
            mockRulesDAO = mock(PlatformRulesDAO.class);
            mockAuctionDAO = mock(SellerAuctionDAO.class);
            // 7-day maximum, so the rule is well inside the 30-day default
            when(mockRulesDAO.get()).thenReturn(new PlatformRules(new BigDecimal("1.00"), 7, null, null));

            servlet = new SellerWrapper();
            servlet.setPlatformRulesDAO(mockRulesDAO);
            servlet.setSellerAuctionDAO(mockAuctionDAO);

            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            when(req.getPathInfo()).thenReturn("/create");
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(9));

            when(req.getParameter("auctionName")).thenReturn("Vintage camera");
            when(req.getParameter("auctionDetails")).thenReturn("Working condition");
            when(req.getParameter("itemCondition")).thenReturn("1");
            when(req.getParameter("startDate")).thenReturn(iso(start));
        }

        @Test
        @DisplayName("a listing longer than the platform maximum → 400")
        void tooLong() throws Exception {
            when(req.getParameter("endDate")).thenReturn(iso(start.plus(10, ChronoUnit.DAYS)));

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("7 day"));
        }

        @Test
        @DisplayName("a listing exactly at the platform maximum passes the duration check")
        void exactlyAtLimit() throws Exception {
            when(req.getParameter("endDate")).thenReturn(iso(start.plus(7, ChronoUnit.DAYS)));

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            // The request proceeds past the duration gate (it fails later, at the DB write).
            JsonNode body = ApiTestSupport.parse(sw);
            if (body.has("error")) {
                assertFalse(body.get("error").asText().contains("duration"));
            }
        }

        @Test
        @DisplayName("seller role is still required before any rule is read")
        void buyerForbidden() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(9));
            when(req.getParameter("endDate")).thenReturn(iso(start.plus(10, ChronoUnit.DAYS)));

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(403);
            verifyNoInteractions(mockRulesDAO);
        }
    }

    @Nested
    @DisplayName("GET /api/seller/rules")
    class SellerReadsRules {

        private PlatformRulesDAO mockRulesDAO;
        private SellerWrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;

        @BeforeEach
        void setUp() {
            mockRulesDAO = mock(PlatformRulesDAO.class);
            when(mockRulesDAO.get()).thenReturn(new PlatformRules(new BigDecimal("2.50"), 7, null, null));
            servlet = new SellerWrapper();
            servlet.setPlatformRulesDAO(mockRulesDAO);
            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            when(req.getPathInfo()).thenReturn("/rules");
        }

        @Test
        @DisplayName("a seller sees the limits their listing will be checked against")
        void sellerSeesRules() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(9));
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);

            verify(resp).setStatus(200);
            JsonNode body = ApiTestSupport.parse(sw);
            assertEquals(0, new BigDecimal("2.50").compareTo(body.get("minBidIncrement").decimalValue()));
            assertEquals(7, body.get("maxAuctionDurationDays").asInt());
        }

        @Test
        @DisplayName("a buyer → 403")
        void buyerForbidden() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(9));
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(403);
            verifyNoInteractions(mockRulesDAO);
        }

        @Test
        @DisplayName("anonymous → 401")
        void anonymous() throws Exception {
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);
            verify(resp).setStatus(401);
            verifyNoInteractions(mockRulesDAO);
        }
    }

    @Nested
    @DisplayName("maximum auction duration — POST /api/seller/edit")
    class EditAuction {

        private PlatformRulesDAO mockRulesDAO;
        private SellerAuctionDAO mockAuctionDAO;
        private SellerWrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;
        private final Instant start = Instant.now().minus(2, ChronoUnit.DAYS);

        @BeforeEach
        void setUp() {
            mockRulesDAO = mock(PlatformRulesDAO.class);
            mockAuctionDAO = mock(SellerAuctionDAO.class);
            when(mockRulesDAO.get()).thenReturn(new PlatformRules(new BigDecimal("1.00"), 7, null, null));

            servlet = new SellerWrapper();
            servlet.setPlatformRulesDAO(mockRulesDAO);
            servlet.setSellerAuctionDAO(mockAuctionDAO);

            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            when(req.getPathInfo()).thenReturn("/edit");
            ApiTestSupport.withBearer(req, ApiTestSupport.newSellerSession(9));

            when(req.getParameter("auctionId")).thenReturn("42");
            when(req.getParameter("title")).thenReturn("Vintage camera");
        }

        private SellerAuctionDAO.AuctionEditData editData() {
            return new SellerAuctionDAO.AuctionEditData(
                    42L, 9L, 1, "Vintage camera", "Working condition", "Cameras", 1,
                    null, start, start.plus(3, ChronoUnit.DAYS), List.of());
        }

        @Test
        @DisplayName("rescheduling past the maximum duration from the start date → 400")
        void rescheduleTooLong() throws Exception {
            when(mockAuctionDAO.getAuctionForEdit(42L, 9)).thenReturn(editData());
            // 10 days after the auction started, i.e. beyond the 7-day rule
            when(req.getParameter("endDate")).thenReturn(iso(start.plus(10, ChronoUnit.DAYS)));

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText().contains("7 day"));
            verify(mockAuctionDAO, never()).editAuction(anyLong(), anyInt(), any(), any(), any(),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("rescheduling inside the maximum duration is saved")
        void rescheduleWithinLimit() throws Exception {
            when(mockAuctionDAO.getAuctionForEdit(42L, 9)).thenReturn(editData());
            when(req.getParameter("endDate")).thenReturn(iso(start.plus(6, ChronoUnit.DAYS)));

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(mockAuctionDAO).editAuction(eq(42L), eq(9), eq("Vintage camera"), any(), any(),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("an auction the seller does not own → 404")
        void notOwned() throws Exception {
            when(mockAuctionDAO.getAuctionForEdit(42L, 9)).thenReturn(null);
            when(req.getParameter("endDate")).thenReturn(iso(start.plus(6, ChronoUnit.DAYS)));

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(404);
        }
    }

    @Nested
    @DisplayName("minimum bid increment — bid rejection")
    class MinimumIncrementRejection {

        @Test
        @DisplayName("a bid below the increment → 400 explaining the platform rule")
        void belowIncrement() throws Exception {
            BidDAO mockDAO = mock(BidDAO.class);
            BidWrapper servlet = new BidWrapper();
            servlet.setBidDAO(mockDAO);
            HttpServletRequest req = mock(HttpServletRequest.class);
            HttpServletResponse resp = mock(HttpServletResponse.class);

            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(5));
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("100.01");
            when(mockDAO.getAuctionTypeId(10L)).thenReturn(AuctionType.PRICE_UP.getId());
            when(mockDAO.placeBid(10L, 5, new BigDecimal("100.01")))
                    .thenReturn(BidResult.BELOW_MIN_INCREMENT);

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText()
                    .contains("minimum bid increment"));
        }

        @Test
        @DisplayName("the JSP path explains the same rule")
        void legacyMessage() {
            assertTrue(PlaceBidServlet.toMessage(BidResult.BELOW_MIN_INCREMENT)
                    .contains("minimum bid increment"));
        }
    }

    @Nested
    @DisplayName("minimum bid increment — proxy bidding")
    class ProxyBidding {

        private AutoBidDAO.AutoBidRow row(int userId, String max, String increment) {
            return new AutoBidDAO.AutoBidRow(userId, new BigDecimal(max),
                    Instant.parse("2026-07-01T00:00:00Z"), new BigDecimal(increment));
        }

        @Test
        @DisplayName("a proxy step smaller than the platform minimum is raised to it")
        void stepRaisedToPlatformMinimum() {
            List<AutoBidDAO.AutoBidRow> bids = List.of(row(1, "200.00", "0.01"));

            AutoBidDAO.CounterBid counter = AutoBidDAO.resolveNextAutoBid(
                    bids, new BigDecimal("100.00"), -1, new BigDecimal("5.00"));

            assertNotNull(counter);
            assertEquals(1, counter.userId);
            assertEquals(0, new BigDecimal("105.00").compareTo(counter.amount));
        }

        @Test
        @DisplayName("a proxy step larger than the platform minimum is kept")
        void largerStepKept() {
            List<AutoBidDAO.AutoBidRow> bids = List.of(row(1, "200.00", "10.00"));

            AutoBidDAO.CounterBid counter = AutoBidDAO.resolveNextAutoBid(
                    bids, new BigDecimal("100.00"), -1, new BigDecimal("5.00"));

            assertNotNull(counter);
            assertEquals(0, new BigDecimal("110.00").compareTo(counter.amount));
        }

        @Test
        @DisplayName("an auto-bidder whose ceiling cannot clear the increment does not fire")
        void ceilingBelowIncrement() {
            // Ceiling beats the floor but not by a full $5.00 increment.
            List<AutoBidDAO.AutoBidRow> bids = List.of(row(1, "103.00", "5.00"));

            assertNull(AutoBidDAO.resolveNextAutoBid(
                    bids, new BigDecimal("100.00"), -1, new BigDecimal("5.00")));
        }

        @Test
        @DisplayName("a ceiling exactly one increment above the floor fires at the ceiling")
        void ceilingExactlyOneIncrement() {
            List<AutoBidDAO.AutoBidRow> bids = List.of(row(1, "105.00", "10.00"));

            AutoBidDAO.CounterBid counter = AutoBidDAO.resolveNextAutoBid(
                    bids, new BigDecimal("100.00"), -1, new BigDecimal("5.00"));

            assertNotNull(counter);
            assertEquals(0, new BigDecimal("105.00").compareTo(counter.amount));
        }

        @Test
        @DisplayName("the legacy 3-argument call still steps by one cent")
        void legacyOverloadUnchanged() {
            List<AutoBidDAO.AutoBidRow> bids = List.of(row(1, "200.00", "0.01"));

            AutoBidDAO.CounterBid counter = AutoBidDAO.resolveNextAutoBid(
                    bids, new BigDecimal("100.00"), -1);

            assertNotNull(counter);
            assertEquals(0, new BigDecimal("100.01").compareTo(counter.amount));
        }
    }
}
