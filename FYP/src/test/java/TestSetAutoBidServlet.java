import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.model.AuctionDetail;
import com.auction.servlet.SetAutoBidServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;

/**
 * Unit tests for {@link SetAutoBidServlet} covering SCRUM-270, SCRUM-271, and SCRUM-296.
 *
 * <h2>RBAC (SCRUM-271)</h2>
 * Only authenticated buyers may set auto-bids. No session, SELLER role, and ADMIN role → 403.
 *
 * <h2>Ended / cancelled auctions (SCRUM-271)</h2>
 * When {@link BidDAO#findByIdForDisplay} returns a detail with {@code open=false},
 * the servlet must redirect with an error flash instead of storing anything.
 *
 * <h2>Competing auto-bids (SCRUM-270)</h2>
 * {@link AutoBidDAO#resolveNextAutoBid} is tested in isolation with multiple auto-bidders
 * to verify:
 * <ul>
 *   <li>Higher max wins with the correct bid amount (one-round leapfrog).</li>
 *   <li>Equal max → earlier {@code created_at} wins (FIFO tiebreak).</li>
 *   <li>Limit reached: winner bids exactly their ceiling when second-best is higher.</li>
 * </ul>
 *
 * <h2>SCRUM-296</h2>
 * userId always from session; auctionId rejected if non-numeric; maxAmount validated
 * server-side before storage; note field encrypted in AutoBidDAO (covered by integration).
 */
@DisplayName("SetAutoBidServlet + AutoBidDAO algorithm – SCRUM-52")
public class TestSetAutoBidServlet {

    /** Exposes the protected {@code doPost} for out-of-package testing. */
    private static class Wrapper extends SetAutoBidServlet {
        Wrapper(AutoBidDAO autoBidDAO, BidDAO bidDAO) { super(autoBidDAO, bidDAO); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private AutoBidDAO mockAutoBidDAO;
    private BidDAO mockBidDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockAutoBidDAO = mock(AutoBidDAO.class);
        mockBidDAO     = mock(BidDAO.class);
        servlet        = new Wrapper(mockAutoBidDAO, mockBidDAO);
        req            = mock(HttpServletRequest.class);
        resp           = mock(HttpServletResponse.class);
        session        = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    // ---------------------------------------------------------------------- helpers

    private void stubBuyerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    private AuctionDetail openAuction(long auctionId, int sellerId, BigDecimal currentBid) {
        return new AuctionDetail(auctionId, "Test Auction", "desc", "Electronics",
                "Brand New", new BigDecimal("10.00"), currentBid, 0, null,
                Instant.now().plusSeconds(3600), sellerId, "seller1",
                Collections.emptyList(), true);
    }

    private AuctionDetail closedAuction(long auctionId) {
        return new AuctionDetail(auctionId, "Old Auction", "desc", "Other",
                "Used", new BigDecimal("10.00"), new BigDecimal("50.00"), 3, null,
                Instant.now().minusSeconds(60), 99, "seller2",
                Collections.emptyList(), false);
    }

    // ==========================================================================
    // RBAC (SCRUM-271)
    // ==========================================================================

    @Nested
    @DisplayName("RBAC – SCRUM-271")
    class Rbac {

        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockAutoBidDAO, mockBidDAO);
        }

        @Test
        @DisplayName("SELLER role → 403")
        void sellerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("SELLER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockAutoBidDAO, mockBidDAO);
        }

        @Test
        @DisplayName("ADMIN role → 403")
        void adminForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("ADMIN");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockAutoBidDAO, mockBidDAO);
        }
    }

    // ==========================================================================
    // Input validation (SCRUM-296)
    // ==========================================================================

    @Nested
    @DisplayName("Input validation – SCRUM-296")
    class InputValidation {

        @Test
        @DisplayName("missing auctionId → 400")
        void missingAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn(null);
            when(req.getParameter("action")).thenReturn("SET");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }

        @Test
        @DisplayName("non-numeric auctionId → 400 (IDOR guard)")
        void nonNumericAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("'; DROP TABLE auto_bids; --");
            when(req.getParameter("action")).thenReturn("SET");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockAutoBidDAO, mockBidDAO);
        }

        @Test
        @DisplayName("missing maxAmount → error redirect")
        void missingMaxAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("autoBidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verifyNoInteractions(mockAutoBidDAO, mockBidDAO);
        }

        @Test
        @DisplayName("non-numeric maxAmount → error redirect")
        void nonNumericMaxAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("not-a-number");
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("autoBidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("zero maxAmount → error redirect")
        void zeroMaxAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("0.00");
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("autoBidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("userId comes from session only (no IDOR via request param)")
        void userIdFromSession() throws Exception {
            stubBuyerSession(42);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("200.00");
            when(req.getParameter("note")).thenReturn(null);
            AuctionDetail auction = openAuction(10L, 99, new BigDecimal("10.00"));
            when(mockBidDAO.findByIdForDisplay(10L)).thenReturn(auction);

            servlet.doPost(req, resp);

            // AutoBidDAO.upsert called with session userId=42, not any other
            verify(mockAutoBidDAO).upsert(eq(10L), eq(42), any(BigDecimal.class), any());
            verify(mockAutoBidDAO, never()).upsert(eq(10L), eq(1), any(), any());
        }
    }

    // ==========================================================================
    // Ended / cancelled auctions (SCRUM-271)
    // ==========================================================================

    @Nested
    @DisplayName("Ended/cancelled auction guard – SCRUM-271")
    class AuctionStateGuard {

        @Test
        @DisplayName("ended auction → error flash, no upsert")
        void endedAuction() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("100.00");
            when(mockBidDAO.findByIdForDisplay(10L)).thenReturn(closedAuction(10L));

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("autoBidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verify(mockAutoBidDAO, never()).upsert(anyLong(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("auction not found → error flash, no upsert")
        void auctionNotFound() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("9999");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("100.00");
            when(mockBidDAO.findByIdForDisplay(9999L)).thenReturn(null);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("autoBidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/9999");
            verify(mockAutoBidDAO, never()).upsert(anyLong(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("maxAmount ≤ current bid → error flash (server-side floor check)")
        void maxTooLow() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("30.00"); // current bid is 50
            AuctionDetail auction = openAuction(10L, 99, new BigDecimal("50.00"));
            when(mockBidDAO.findByIdForDisplay(10L)).thenReturn(auction);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("autoBidFlashError"),
                    argThat(msg -> msg.toString().contains("50")));
            verify(mockAutoBidDAO, never()).upsert(anyLong(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("seller cannot set auto-bid on own auction → error flash")
        void selfBid() throws Exception {
            stubBuyerSession(99); // seller is also buyer here
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("100.00");
            AuctionDetail auction = openAuction(10L, 99 /* sellerId = userId */, new BigDecimal("10.00"));
            when(mockBidDAO.findByIdForDisplay(10L)).thenReturn(auction);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("autoBidFlashError"), anyString());
            verify(mockAutoBidDAO, never()).upsert(anyLong(), anyInt(), any(), any());
        }
    }

    // ==========================================================================
    // Successful set / cancel
    // ==========================================================================

    @Nested
    @DisplayName("Successful set and cancel")
    class SuccessFlow {

        @Test
        @DisplayName("valid buyer + open auction + valid amount → upsert called + success flash")
        void successfulSet() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("150.00");
            when(req.getParameter("note")).thenReturn("budget limit");
            AuctionDetail auction = openAuction(10L, 99, new BigDecimal("10.00"));
            when(mockBidDAO.findByIdForDisplay(10L)).thenReturn(auction);

            servlet.doPost(req, resp);

            ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> noteCaptor    = ArgumentCaptor.forClass(String.class);
            verify(mockAutoBidDAO).upsert(eq(10L), eq(5), amtCaptor.capture(), noteCaptor.capture());
            assertEquals(0, amtCaptor.getValue().compareTo(new BigDecimal("150.00")));
            assertEquals("budget limit", noteCaptor.getValue());
            verify(session).setAttribute(eq("autoBidFlash"),
                    argThat(msg -> msg.toString().contains("150")));
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("update existing auto-bid → upsert called with new amount")
        void updateExisting() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("250.00");
            when(req.getParameter("note")).thenReturn(null);
            AuctionDetail auction = openAuction(10L, 99, new BigDecimal("20.00"));
            when(mockBidDAO.findByIdForDisplay(10L)).thenReturn(auction);

            servlet.doPost(req, resp);

            verify(mockAutoBidDAO).upsert(eq(10L), eq(5),
                    argThat(v -> v.compareTo(new BigDecimal("250.00")) == 0), isNull());
        }

        @Test
        @DisplayName("cancel auto-bid → delete called + success flash + redirect")
        void cancelAutoBid() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("CANCEL");

            servlet.doPost(req, resp);

            verify(mockAutoBidDAO).delete(10L, 5);
            verify(session).setAttribute(eq("autoBidFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verify(mockBidDAO, never()).findByIdForDisplay(anyLong());
        }
    }

    // ==========================================================================
    // resolveNextAutoBid algorithm (one-step ping-pong mode)
    // ==========================================================================

    @Nested
    @DisplayName("AutoBidDAO.resolveNextAutoBid algorithm – one-step ping-pong")
    class AlgorithmTests {

        private final Instant T0 = Instant.ofEpochSecond(1_000_000);
        private final Instant T1 = Instant.ofEpochSecond(1_000_001); // later

        private AutoBidDAO.AutoBidRow row(int userId, String max, Instant created) {
            return new AutoBidDAO.AutoBidRow(userId, new BigDecimal(max), created);
        }

        @Test
        @DisplayName("no auto-bids → null (nothing fires)")
        void noAutoBids() {
            assertNull(AutoBidDAO.resolveNextAutoBid(
                    Collections.emptyList(), new BigDecimal("10.00"), -1));
        }

        @Test
        @DisplayName("single auto-bidder who can beat floor → fires at floor + 0.01")
        void singleAutoBidder() {
            var bids = java.util.List.of(row(1, "100.00", T0));
            var result = AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("10.00"), -1);

            assertNotNull(result);
            assertEquals(1, result.userId);
            // secondBestMax = floor ($10), counter = max($10.01, $10.01) = $10.01
            assertEquals(0, result.amount.compareTo(new BigDecimal("10.01")));
        }

        @Test
        @DisplayName("single auto-bidder who IS current top bidder → null (no self-counter)")
        void currentTopBidderExcluded() {
            var bids = java.util.List.of(row(1, "100.00", T0));
            // currentTopBidder = 1 (the same user)
            assertNull(AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("50.00"), 1));
        }

        @Test
        @DisplayName("auto-bidder max ≤ floor → null (cannot beat current price)")
        void autoBidBelowFloor() {
            var bids = java.util.List.of(row(2, "30.00", T0));
            // floor = $50 (manual bid); auto-bidder max $30 can't beat it
            assertNull(AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("50.00"), 1));
        }

        @Test
        @DisplayName("auto-bidder max equals floor → null (must be strictly greater)")
        void autoBidEqualsFloor() {
            var bids = java.util.List.of(row(2, "50.00", T0));
            assertNull(AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("50.00"), 1));
        }

        /**
         * One-step mode: highest-max competitor bids floor + increment only.
         * B($150) vs A($100) at floor $10 → B's first counter is $10.01 (not $100.01).
         * processAutoBids loop repeats until equilibrium.
         */
        @Test
        @DisplayName("competing auto-bids: higher max wins at floor + increment (one step)")
        void competingHigherMaxWins() {
            var bids = java.util.List.of(
                    row(1, "100.00", T0),   // A
                    row(2, "150.00", T1));  // B (created later)
            var result = AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("10.00"), -1);

            assertNotNull(result);
            assertEquals(2, result.userId); // B wins (higher max)
            assertEquals(0, result.amount.compareTo(new BigDecimal("10.01")));
        }

        /**
         * One-step mode: equal max → earlier created_at wins; bids floor + increment.
         */
        @Test
        @DisplayName("equal max: earlier created_at (FIFO) wins at floor + increment")
        void equalMaxFifoTiebreak() {
            var bids = java.util.List.of(
                    row(1, "100.00", T0),   // A — earlier
                    row(2, "100.00", T1));  // B — later
            var result = AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("10.00"), -1);

            assertNotNull(result);
            assertEquals(1, result.userId); // A wins (earlier)
            assertEquals(0, result.amount.compareTo(new BigDecimal("10.01")));
        }

        /**
         * One-step mode: challenger bids floor + increment, capped at their max.
         * B($150) is current top bidder at $79. A($80) counters one step to $79.01.
         */
        @Test
        @DisplayName("limit reached: challenger bids floor + increment capped at max")
        void limitReached() {
            var bids = java.util.List.of(
                    row(1, "80.00",  T0),   // A — winner candidate
                    row(2, "150.00", T1));  // B — current top bidder (excluded)
            var result = AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("79.00"), 2);

            assertNotNull(result);
            assertEquals(1, result.userId); // A counters
            assertEquals(0, result.amount.compareTo(new BigDecimal("79.01")));
        }

        /**
         * One-step mode: after A bids $79.01, B counter-bids $79.02 (second loop iteration).
         */
        @Test
        @DisplayName("after limit step: B counter-bids one increment above new floor")
        void afterLimitReachedBCounters() {
            var bids = java.util.List.of(
                    row(1, "80.00",  T0),   // A — current top bidder
                    row(2, "150.00", T1));  // B — challenger
            var result = AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("79.01"), 1);

            assertNotNull(result);
            assertEquals(2, result.userId); // B wins
            assertEquals(0, result.amount.compareTo(new BigDecimal("79.02")));
        }

        @Test
        @DisplayName("three auto-bidders: highest max wins at floor + increment (one step)")
        void threeAutoBidders() {
            var bids = java.util.List.of(
                    row(1, "100.00", T0),
                    row(2, "150.00", T0.plusSeconds(1)),
                    row(3, "200.00", T0.plusSeconds(2)));
            var result = AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("10.00"), -1);

            assertNotNull(result);
            assertEquals(3, result.userId); // C wins
            assertEquals(0, result.amount.compareTo(new BigDecimal("10.01")));
        }

        @Test
        @DisplayName("no auto-bidder can beat existing top bid → null")
        void noneCanBeat() {
            var bids = java.util.List.of(
                    row(2, "45.00", T0));  // max $45, floor already $50
            assertNull(AutoBidDAO.resolveNextAutoBid(bids, new BigDecimal("50.00"), 1));
        }
    }
}
