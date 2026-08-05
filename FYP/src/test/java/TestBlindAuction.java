import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.dao.BrowseHistoryDAO;
import com.auction.dao.FeaturedListingDAO;
import com.auction.dao.OrderDAO;
import com.auction.dao.QuestionDAO;
import com.auction.dao.RecommendationDAO;
import com.auction.dao.SearchDAO;
import com.auction.dao.SellerProfileDAO;
import com.auction.dao.WatchlistDAO;
import com.auction.dao.AuctionTagsDAO;
import com.auction.model.AuctionDetail;
import com.auction.model.AuctionStatus;
import com.auction.model.AuctionType;
import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.model.SearchSort;
import com.auction.notification.NotificationService;
import com.auction.realtime.AuctionEventPublisher;
import com.auction.servlet.api.AuctionApiServlet;
import com.auction.servlet.api.AutoBidApiServlet;
import com.auction.servlet.api.BidApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuctionFinalizer;
import com.auction.util.AuthSession;
import com.auction.util.DBUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Blind (sealed-bid) auctions — {@link AuctionType#BLIND}.
 *
 * <p>The defining property of the mechanism is that a bidder cannot see what anyone else
 * has bid while the auction is open. That is not one check in one place: the standing bid
 * is derivable from the detail payload, the bid-history page, the live SSE snapshot, and
 * every listing projection that computes a price from {@code MAX(bid_amount)}. These tests
 * pin the guard on each of those read paths, then cover the write side — one sealed bid per
 * buyer, no ascending-style outbid notifications — and the close, where the highest sealed
 * bid becomes the winner and the amount is finally revealed.</p>
 *
 * <p>A sweep of every read path that projects a price turned up five that reached a live
 * blind auction's leading bid without the guard, including two that could be read with no
 * session at all and one that gave the amount away through an error message rather than a
 * payload. All five are fixed; {@code ConfidentialityRegressions} below pins each one, and
 * {@code AutoBidDoesNotApply} covers the related case of proxy bidding, which cannot work on
 * a sealed auction and is now refused rather than silently stored.</p>
 *
 * <p><b>What these tests do not cover.</b> The tie-break at close is decided by an
 * {@code ORDER BY} the database evaluates, so it is pinned as a query shape rather than as
 * an outcome — the suite has no database.</p>
 */
@DisplayName("Blind (sealed-bid) auctions")
class TestBlindAuction {

    private static final long AUCTION_ID = 10L;
    private static final int  SELLER     = 42;
    private static final int  BIDDER     = 5;
    private static final int  OTHER_BIDDER = 7;

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    /** An {@link AuctionDetail} of the given type whose standing bid is {@code currentBid}. */
    private static AuctionDetail detail(AuctionType type, boolean open,
                                        String currentBid, int bidCount) {
        AuctionDetail d = new AuctionDetail(
                AUCTION_ID, "Signed guitar", "One of a kind", "Music", "Brand New",
                new BigDecimal("100.00"), new BigDecimal(currentBid), bidCount,
                null, Instant.now().plusSeconds(3600), SELLER, "sellerBob",
                Collections.emptyList(), open);
        d.setAuctionTypeId(type.getId());
        d.setDateCreated(Instant.now().minusSeconds(3600));
        return d;
    }

    /** JSON numbers come back without their trailing zeros, so compare by value not by scale. */
    private static void assertAmount(String expected, JsonNode actual) {
        assertNotNull(actual, "expected an amount, found no field at all");
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual.asText());
    }

    /** Assertions run inside the static-mock scope a POST to the bid API needs. */
    @FunctionalInterface
    private interface MockedStaticBlock {
        void check(MockedStatic<NotificationService> notifications) throws Exception;
    }

    // =========================================================================
    // The detail API — the payload a buyer's auction page is built from
    // =========================================================================

    @Nested
    @DisplayName("Detail API — the standing bid is sealed while the auction is open")
    class SealedDetail {

        private BidDAO bidDAO;
        private Wrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;

        /** {@code doGet} is protected on the servlet; widen it so the test can drive it. */
        private class Wrapper extends AuctionApiServlet {
            @Override public void doGet(HttpServletRequest r, HttpServletResponse s)
                    throws java.io.IOException { super.doGet(r, s); }
        }

        /** The servlet builds its own DAOs in the constructor; swap every one for a mock. */
        private void inject(String name, Object value) throws Exception {
            Field f = AuctionApiServlet.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(servlet, value);
        }

        @BeforeEach
        void setUp() throws Exception {
            bidDAO  = mock(BidDAO.class);
            servlet = new Wrapper();
            inject("bidDAO",           bidDAO);
            inject("questionDAO",      mock(QuestionDAO.class));
            inject("tagsDAO",          mock(AuctionTagsDAO.class));
            inject("browseHistoryDAO", mock(BrowseHistoryDAO.class));
            inject("autoBidDAO",       mock(AutoBidDAO.class));
            inject("orderDAO",         mock(OrderDAO.class));

            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            when(req.getPathInfo()).thenReturn("/" + AUCTION_ID);
        }

        private JsonNode get() throws Exception {
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            try (MockedStatic<AuctionFinalizer> ignored = mockStatic(AuctionFinalizer.class)) {
                servlet.doGet(req, resp);
            }
            return ApiTestSupport.parse(sw);
        }

        private void viewer(int userId) {
            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(userId));
        }

        @Test
        @DisplayName("A rival bidder is given no amount at all, only how many sealed bids exist")
        void openBlindHidesTheStandingBidFromRivalBidders() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));
            viewer(BIDDER);

            JsonNode body = get();

            assertTrue(body.get("currentBid").isNull(),
                    "the leading sealed bid must not reach a rival bidder");
            assertTrue(body.get("sealed").asBoolean(), "the page must know to render a sealed state");
            assertEquals(3, body.get("numBids").asInt(), "the count is public; the amounts are not");
        }

        @Test
        @DisplayName("No auto-bid is echoed back, even where a row predating the guard survives")
        void openBlindDoesNotEchoAnAutoBid() throws Exception {
            AutoBidDAO autoBidDAO = mock(AutoBidDAO.class);
            inject("autoBidDAO", autoBidDAO);
            when(autoBidDAO.getAutoBidForUser(AUCTION_ID, BIDDER)).thenReturn(
                    new AutoBidDAO.AutoBidRow(BIDDER, new BigDecimal("900.00"), Instant.now()));
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));
            viewer(BIDDER);

            JsonNode body = get();

            assertNull(body.get("myAutoBid"),
                    "proxy bidding never runs on a sealed auction, so showing an active "
                            + "auto-bid would promise the buyer a defence they do not have");
        }

        @Test
        @DisplayName("An unregistered visitor is given no amount either")
        void openBlindHidesTheStandingBidFromAnonymousVisitors() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));

            JsonNode body = get();

            assertTrue(body.get("currentBid").isNull());
            assertTrue(body.get("sealed").asBoolean());
            assertFalse(body.get("isOwner").asBoolean());
        }

        @Test
        @DisplayName("The seller who listed it does see the standing bid — they sell at that price")
        void theSellerSeesTheStandingBidOnTheirOwnListing() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));
            viewer(SELLER);

            JsonNode body = get();

            assertTrue(body.get("isOwner").asBoolean());
            assertAmount("250.00", body.get("currentBid"));
            assertFalse(body.get("sealed").asBoolean(), "nothing is sealed from the owner");
        }

        @Test
        @DisplayName("Once the auction closes the winning amount is revealed to everyone")
        void closedBlindRevealsTheWinningBid() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, false, "250.00", 3));
            viewer(BIDDER);

            JsonNode body = get();

            assertAmount("250.00", body.get("currentBid"));
            assertFalse(body.get("sealed").asBoolean());
        }

        @Test
        @DisplayName("A bidder is shown their own sealed bid and still not the leading one")
        void aBidderSeesTheirOwnBidButNotTheOthers() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));
            when(bidDAO.getUserBidAmount(AUCTION_ID, BIDDER)).thenReturn(new BigDecimal("120.00"));
            viewer(BIDDER);

            JsonNode body = get();

            assertTrue(body.get("mySealedBid").asBoolean());
            assertAmount("120.00", body.get("mySealedBidAmount"));
            assertTrue(body.get("currentBid").isNull(),
                    "seeing your own bid must not reveal the auction's leading bid");
        }

        @Test
        @DisplayName("A bidder who has not bid yet is told so, and learns nothing else")
        void aNonBidderIsToldTheyHaveNotBid() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));
            when(bidDAO.getUserBidAmount(AUCTION_ID, BIDDER)).thenReturn(null);
            viewer(BIDDER);

            JsonNode body = get();

            assertFalse(body.get("mySealedBid").asBoolean());
            assertNull(body.get("mySealedBidAmount"));
            assertTrue(body.get("currentBid").isNull());
        }

        @Test
        @DisplayName("The type is named on the payload, so the page can explain the mechanism")
        void theBlindTypeIsNamedOnThePayload() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));

            JsonNode body = get();

            assertEquals(AuctionType.BLIND.getId(), body.get("auctionType").asInt());
            assertEquals("Blind (Sealed Bid)", body.get("auctionTypeName").asText());
        }

        @Test
        @DisplayName("An ascending auction is never sealed — the guard is type-specific, not blanket")
        void anAscendingAuctionIsNeverSealed() throws Exception {
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.PRICE_UP, true, "250.00", 3));
            viewer(BIDDER);

            JsonNode body = get();

            assertAmount("250.00", body.get("currentBid"));
            assertNull(body.get("sealed"), "only blind listings carry the sealed flag");
        }

        // ── /api/auction/{id}/bids ───────────────────────────────────────────

        @Test
        @DisplayName("The bid history of an open blind auction returns no rows, only a total")
        void openBlindBidHistoryReturnsNoRows() throws Exception {
            when(req.getPathInfo()).thenReturn("/" + AUCTION_ID + "/bids");
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, true, "250.00", 3));

            JsonNode body = get();

            assertTrue(body.get("bids").isEmpty(), "no bid rows may leave the server while sealed");
            assertTrue(body.get("sealed").asBoolean());
            assertEquals(3, body.get("total").asInt());
            verify(bidDAO, never()).getBidHistory(anyLong(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Once closed, the bid history is served like any other auction's")
        void closedBlindBidHistoryIsServedNormally() throws Exception {
            when(req.getPathInfo()).thenReturn("/" + AUCTION_ID + "/bids");
            when(bidDAO.findByIdForDisplay(AUCTION_ID))
                    .thenReturn(detail(AuctionType.BLIND, false, "250.00", 3));
            when(bidDAO.getBidHistory(eq(AUCTION_ID), anyInt(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(bidDAO.countBidHistory(AUCTION_ID)).thenReturn(3);

            JsonNode body = get();

            assertNull(body.get("sealed"));
            assertEquals(3, body.get("total").asInt());
            verify(bidDAO).getBidHistory(eq(AUCTION_ID), anyInt(), anyInt(), anyInt());
        }
    }

    // =========================================================================
    // Listing projections — search, recommendations, featured
    // =========================================================================

    @Nested
    @DisplayName("Listing projections resolve a blind listing to its entry price")
    class ListingProjections {

        private Connection conn;
        private PreparedStatement ps;
        private ResultSet rs;

        @BeforeEach
        void setUp() throws Exception {
            conn = mock(Connection.class);
            ps   = mock(PreparedStatement.class);
            rs   = mock(ResultSet.class);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(false);
        }

        private List<String> preparedSql() throws Exception {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn, atLeastOnce()).prepareStatement(sql.capture());
            return sql.getAllValues();
        }

        /**
         * Every projection that computes a listing price does so over open auctions only, so
         * each one must resolve a blind row to its starting price instead of the leading bid.
         */
        private void assertEveryPriceColumnIsSealedSafe() throws Exception {
            List<String> priced = preparedSql().stream()
                    .filter(s -> s.contains("AS current_price"))
                    .collect(Collectors.toList());

            assertFalse(priced.isEmpty(), "expected at least one query to project a price column");
            for (String sql : priced) {
                assertTrue(sql.contains("a.auction_type = " + AuctionType.BLIND.getId()),
                        "a price column with no blind guard leaks the leading sealed bid: " + sql);
                assertTrue(sql.contains("THEN d.starting_price"),
                        "a blind listing must resolve to its entry price: " + sql);
            }
        }

        @Test
        @DisplayName("Search never projects the leading sealed bid")
        void searchResolvesABlindListingToItsStartingPrice() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new SearchDAO().search("guitar", null, null, SearchSort.DEFAULT, 1, 10);
            }
            assertEveryPriceColumnIsSealedSafe();
        }

        @Test
        @DisplayName("The price filter runs on the same sealed-safe column, so it cannot probe the bid")
        void thePriceFilterCannotProbeTheSealedBid() throws Exception {
            SearchFilter filter = SearchFilter.builder()
                    .minPrice(new BigDecimal("200"))
                    .maxPrice(new BigDecimal("300"))
                    .build();

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new SearchDAO().search("guitar", null, filter, SearchSort.DEFAULT, 1, 10);
            }
            assertEveryPriceColumnIsSealedSafe();
        }

        @Test
        @DisplayName("The trending strip never projects the leading sealed bid")
        void trendingResolvesABlindListingToItsStartingPrice() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new RecommendationDAO().trending(12, Collections.emptySet(), null);
            }
            assertEveryPriceColumnIsSealedSafe();
        }

        @Test
        @DisplayName("The featured strip never projects the leading sealed bid")
        void featuredResolvesABlindListingToItsStartingPrice() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new FeaturedListingDAO().listActiveFeatured(12);
            }
            assertEveryPriceColumnIsSealedSafe();
        }

        @Test
        @DisplayName("A blind row on a listing card is flagged sealed so the UI does not call it a bid")
        void aBlindRowIsFlaggedSealed() {
            SearchResultItem blind = new SearchResultItem(
                    AUCTION_ID, "Signed guitar", "Music", new BigDecimal("100.00"),
                    Instant.now().plusSeconds(3600), "sellerBob", null, AuctionType.BLIND.getId());
            SearchResultItem ascending = new SearchResultItem(
                    AUCTION_ID, "Signed guitar", "Music", new BigDecimal("100.00"),
                    Instant.now().plusSeconds(3600), "sellerBob", null, AuctionType.PRICE_UP.getId());

            assertTrue(blind.isSealed());
            assertFalse(ascending.isSealed());
        }
    }

    // =========================================================================
    // Submitting a sealed bid
    // =========================================================================

    @Nested
    @DisplayName("Submitting a sealed bid")
    class SubmittingASealedBid {

        private BidDAO bidDAO;
        private Wrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;

        private class Wrapper extends BidApiServlet {
            @Override public void doPost(HttpServletRequest r, HttpServletResponse s)
                    throws java.io.IOException { super.doPost(r, s); }
        }

        @BeforeEach
        void setUp() throws Exception {
            bidDAO  = mock(BidDAO.class);
            servlet = new Wrapper();
            servlet.setBidDAO(bidDAO);
            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);

            AuthSession session = ApiTestSupport.newBuyerSession(BIDDER);
            ApiTestSupport.withBearer(req, session);
            when(req.getParameter("auctionId")).thenReturn(String.valueOf(AUCTION_ID));
            when(bidDAO.getAuctionTypeId(AUCTION_ID)).thenReturn(AuctionType.BLIND.getId());
        }

        private StringWriter post(MockedStaticBlock block) throws Exception {
            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            try (MockedStatic<AuctionEventPublisher> ignored = mockStatic(AuctionEventPublisher.class);
                 MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
                servlet.doPost(req, resp);
                block.check(notifications);
            }
            return sw;
        }

        @Test
        @DisplayName("A sealed bid is accepted and acknowledged without revealing anything")
        void aSealedBidIsAcknowledgedWithoutRevealingAnything() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("250.00");
            when(bidDAO.placeSealedBid(AUCTION_ID, BIDDER, new BigDecimal("250.00")))
                    .thenReturn(BidResult.SUCCESS);

            StringWriter sw = post(n -> { });

            verify(resp).setStatus(200);
            verify(bidDAO).placeSealedBid(AUCTION_ID, BIDDER, new BigDecimal("250.00"));
            String message = ApiTestSupport.parse(sw).get("message").asText();
            assertTrue(message.contains("sealed"), "the confirmation should name the mechanism");
            assertFalse(message.contains("250"),
                    "the acknowledgement must not echo a standing price back to the bidder");
        }

        @Test
        @DisplayName("A blind bid routes to the sealed path, never the ascending one")
        void aBlindBidNeverTakesTheAscendingPath() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("250.00");
            when(bidDAO.placeSealedBid(anyLong(), anyInt(), any())).thenReturn(BidResult.SUCCESS);

            post(n -> { });

            verify(bidDAO, never()).placeBid(anyLong(), anyInt(), any());
        }

        @Test
        @DisplayName("A sealed bid tells nobody they were outbid — that would leak the standing bid")
        void aSealedBidNotifiesNoOutbidBidder() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("250.00");
            when(bidDAO.placeSealedBid(anyLong(), anyInt(), any())).thenReturn(BidResult.SUCCESS);

            post(notifications -> {
                notifications.verify(() -> NotificationService.notifyOutbid(anyLong(), anyInt()), never());
                notifications.verify(
                        () -> NotificationService.notifySellerNewBid(anyLong(), any(BigDecimal.class)),
                        never());
            });
        }

        @Test
        @DisplayName("A sealed bid does not conclude the auction the way Buy It Now or a Dutch accept does")
        void aSealedBidDoesNotConcludeTheAuction() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("250.00");
            when(bidDAO.placeSealedBid(anyLong(), anyInt(), any())).thenReturn(BidResult.SUCCESS);

            post(notifications -> {
                notifications.verify(() -> NotificationService.notifyAuctionWon(anyLong(), anyInt()), never());
                notifications.verify(() -> NotificationService.notifyAuctionLost(anyLong(), anyInt()), never());
            });
        }

        @Test
        @DisplayName("A second sealed bid from the same buyer is refused — one bid each")
        void aSecondSealedBidFromTheSameBuyerIsRefused() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("300.00");
            when(bidDAO.placeSealedBid(anyLong(), anyInt(), any())).thenReturn(BidResult.ALREADY_BID);

            StringWriter sw = post(n -> { });

            verify(resp).setStatus(400);
            assertTrue(ApiTestSupport.parse(sw).get("error").asText()
                    .contains("already submitted a sealed bid"));
        }

        @Test
        @DisplayName("A bid below the entry price is refused")
        void aBidBelowTheEntryPriceIsRefused() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("50.00");
            when(bidDAO.placeSealedBid(anyLong(), anyInt(), any())).thenReturn(BidResult.BID_TOO_LOW);

            post(n -> { });

            verify(resp).setStatus(400);
        }

        @Test
        @DisplayName("A blind auction still refuses a bid from the seller who listed it")
        void theSellerCannotBidOnTheirOwnBlindAuction() throws Exception {
            when(req.getParameter("bidAmount")).thenReturn("250.00");
            when(bidDAO.placeSealedBid(anyLong(), anyInt(), any())).thenReturn(BidResult.SELF_BID);

            post(n -> { });

            verify(resp).setStatus(400);
        }

        @Test
        @DisplayName("An admin cannot submit a sealed bid, the same as any other auction type")
        void anAdminCannotSubmitASealedBid() throws Exception {
            ApiTestSupport.withBearer(req, ApiTestSupport.newAdminSession(99));
            when(req.getParameter("bidAmount")).thenReturn("250.00");

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(403);
            verifyNoInteractions(bidDAO);
        }
    }

    // =========================================================================
    // BidDAO.placeSealedBid — the rules the servlet delegates
    // =========================================================================

    @Nested
    @DisplayName("BidDAO.placeSealedBid — the rules a sealed bid is held to")
    class SealedBidRules {

        private Connection conn;
        private PreparedStatement ps;
        private ResultSet rs;

        @BeforeEach
        void setUp() throws Exception {
            conn = mock(Connection.class);
            ps   = mock(PreparedStatement.class);
            rs   = mock(ResultSet.class);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
        }

        /**
         * A live blind auction owned by {@link #SELLER}. {@code alreadyBid} decides what the
         * "one bid per buyer" existence check finds on the second query.
         */
        private void stubLiveBlindAuction(String startingPrice, boolean alreadyBid) throws Exception {
            when(rs.next()).thenReturn(true, alreadyBid);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
            when(rs.getString("moderation_state")).thenReturn("active");
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getInt("auction_type")).thenReturn(AuctionType.BLIND.getId());
            when(rs.getBigDecimal("starting_price")).thenReturn(new BigDecimal(startingPrice));
        }

        private BidResult place(String amount) {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                return new BidDAO().placeSealedBid(AUCTION_ID, BIDDER, new BigDecimal(amount));
            }
        }

        @Test
        @DisplayName("A bid above the entry price is recorded")
        void aBidAboveTheEntryPriceIsRecorded() throws Exception {
            stubLiveBlindAuction("100.00", false);

            assertEquals(BidResult.SUCCESS, place("250.00"));

            ArgumentCaptor<BigDecimal> stored = ArgumentCaptor.forClass(BigDecimal.class);
            verify(ps).setBigDecimal(eq(3), stored.capture());
            assertEquals(new BigDecimal("250.00"), stored.getValue());
            verify(conn).commit();
        }

        @Test
        @DisplayName("A bid at exactly the entry price is accepted")
        void aBidAtExactlyTheEntryPriceIsAccepted() throws Exception {
            stubLiveBlindAuction("100.00", false);

            assertEquals(BidResult.SUCCESS, place("100.00"));
        }

        @Test
        @DisplayName("A bid one cent under the entry price is refused")
        void aBidOneCentUnderTheEntryPriceIsRefused() throws Exception {
            stubLiveBlindAuction("100.00", false);

            assertEquals(BidResult.BID_TOO_LOW, place("99.99"));
            verify(conn, never()).commit();
        }

        @Test
        @DisplayName("A zero or negative amount is refused before the database is touched")
        void aZeroAmountIsRefusedWithoutTouchingTheDatabase() {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                assertEquals(BidResult.BID_TOO_LOW,
                        new BidDAO().placeSealedBid(AUCTION_ID, BIDDER, BigDecimal.ZERO));
                assertEquals(BidResult.BID_TOO_LOW,
                        new BidDAO().placeSealedBid(AUCTION_ID, BIDDER, new BigDecimal("-1")));
            }
            verifyNoInteractions(conn);
        }

        @Test
        @DisplayName("A buyer who has already bid is refused rather than allowed to revise")
        void aBuyerWhoAlreadyBidIsRefused() throws Exception {
            stubLiveBlindAuction("100.00", true);

            assertEquals(BidResult.ALREADY_BID, place("300.00"));
            verify(conn, never()).commit();
            verify(ps, never()).executeUpdate();
        }

        @Test
        @DisplayName("The seller is refused a bid on their own sealed auction")
        void theSellerIsRefused() throws Exception {
            stubLiveBlindAuction("100.00", false);

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                assertEquals(BidResult.SELF_BID,
                        new BidDAO().placeSealedBid(AUCTION_ID, SELLER, new BigDecimal("250.00")));
            }
        }

        @Test
        @DisplayName("A sealed bid after the closing time is refused")
        void aBidAfterTheCloseIsRefused() throws Exception {
            stubLiveBlindAuction("100.00", false);
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));

            assertEquals(BidResult.AUCTION_CLOSED, place("250.00"));
        }

        @Test
        @DisplayName("A sealed bid on a removed listing is refused")
        void aBidOnARemovedListingIsRefused() throws Exception {
            stubLiveBlindAuction("100.00", false);
            when(rs.getString("moderation_state")).thenReturn("removed");

            assertEquals(BidResult.AUCTION_REMOVED, place("250.00"));
        }

        @Test
        @DisplayName("The sealed path refuses an auction that is not blind")
        void theSealedPathRefusesANonBlindAuction() throws Exception {
            stubLiveBlindAuction("100.00", false);
            when(rs.getInt("auction_type")).thenReturn(AuctionType.PRICE_UP.getId());

            assertEquals(BidResult.WRONG_AUCTION_TYPE, place("250.00"));
        }

        @Test
        @DisplayName("Buy It Now does not apply to a blind auction")
        void buyItNowDoesNotApplyToABlindAuction() throws Exception {
            when(rs.next()).thenReturn(true);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
            when(rs.getString("moderation_state")).thenReturn("active");
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getInt("auction_type")).thenReturn(AuctionType.BLIND.getId());
            when(rs.getBigDecimal("buy_it_now_price")).thenReturn(new BigDecimal("500.00"));

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                assertEquals(BidResult.WRONG_AUCTION_TYPE,
                        new BidDAO().buyItNow(AUCTION_ID, BIDDER));
            }
        }

        @Test
        @DisplayName("A Dutch acceptance does not apply to a blind auction")
        void dutchAcceptanceDoesNotApplyToABlindAuction() throws Exception {
            when(rs.next()).thenReturn(true);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_created"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(3600)));
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
            when(rs.getString("moderation_state")).thenReturn("active");
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getInt("auction_type")).thenReturn(AuctionType.BLIND.getId());

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                assertEquals(BidResult.WRONG_AUCTION_TYPE,
                        new BidDAO().acceptDutchBid(AUCTION_ID, BIDDER));
            }
        }
    }

    // =========================================================================
    // The close — who wins, and what is revealed
    // =========================================================================

    @Nested
    @DisplayName("Closing a blind auction")
    class Closing {

        private Connection conn;
        private PreparedStatement ps;
        private ResultSet rs;

        @BeforeEach
        void setUp() throws Exception {
            conn = mock(Connection.class);
            ps   = mock(PreparedStatement.class);
            rs   = mock(ResultSet.class);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
        }

        private List<String> preparedSql() throws Exception {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn, atLeastOnce()).prepareStatement(sql.capture());
            return sql.getAllValues();
        }

        /** An expired blind auction whose top sealed bid belongs to {@code winner}. */
        private void stubExpiredAuctionWithTopBid(int winner, String topBid) throws Exception {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));
            when(rs.getInt("user_id")).thenReturn(winner);
            when(rs.getBigDecimal("bid_amount")).thenReturn(new BigDecimal(topBid));
        }

        @Test
        @DisplayName("The highest sealed bid wins, and its amount becomes the winning bid")
        void theHighestSealedBidWins() throws Exception {
            stubExpiredAuctionWithTopBid(OTHER_BIDDER, "250.00");

            AuctionFinalizer.FinalizeResult r = AuctionFinalizer.finalizeIfEnded(conn, AUCTION_ID);

            assertTrue(r.finalized);
            assertEquals(OTHER_BIDDER, r.winnerId);
            ArgumentCaptor<BigDecimal> written = ArgumentCaptor.forClass(BigDecimal.class);
            verify(ps, atLeastOnce()).setBigDecimal(eq(2), written.capture());
            assertEquals(new BigDecimal("250.00"), written.getAllValues().get(0));
        }

        @Test
        @DisplayName("Exactly one bid is promoted — the losing sealed bids are never written anywhere")
        void losingSealedBidsAreNeverWritten() throws Exception {
            stubExpiredAuctionWithTopBid(OTHER_BIDDER, "250.00");

            AuctionFinalizer.finalizeIfEnded(conn, AUCTION_ID);

            ArgumentCaptor<BigDecimal> written = ArgumentCaptor.forClass(BigDecimal.class);
            verify(ps, atLeastOnce()).setBigDecimal(eq(2), written.capture());
            assertEquals(1, written.getAllValues().size(),
                    "only the winning sealed bid is promoted onto the auction");
        }

        @Test
        @DisplayName("The winner is picked by amount, with the earlier bid breaking a tie")
        void tiesAreBrokenByWhoBidFirst() throws Exception {
            stubExpiredAuctionWithTopBid(OTHER_BIDDER, "250.00");

            AuctionFinalizer.finalizeIfEnded(conn, AUCTION_ID);

            assertTrue(preparedSql().stream().anyMatch(
                            s -> s.contains("ORDER BY bid_amount DESC, bid_time ASC")),
                    "the winner query must rank by amount and settle a tie on bid time");
        }

        @Test
        @DisplayName("Concluding the sale takes a unit of stock")
        void concludingTakesAUnitOfStock() throws Exception {
            stubExpiredAuctionWithTopBid(OTHER_BIDDER, "250.00");

            AuctionFinalizer.finalizeIfEnded(conn, AUCTION_ID);

            assertTrue(preparedSql().stream().anyMatch(s -> s.contains("GREATEST(quantity - 1, 0)")));
        }

        @Test
        @DisplayName("The winner's order is raised for the winning sealed bid, to the cent")
        void theWinnersOrderIsRaisedForTheSealedBid() throws Exception {
            // next() in order: the auction lock row, the top sealed bid, the "is there
            // already an order" probe (none), then the row the order is built from.
            when(rs.next()).thenReturn(true, true, false, true);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));
            when(rs.getInt("user_id")).thenReturn(OTHER_BIDDER);
            when(rs.getBigDecimal("bid_amount")).thenReturn(new BigDecimal("250.00"));
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getInt("winner_id")).thenReturn(OTHER_BIDDER);
            when(rs.wasNull()).thenReturn(false);
            when(rs.getBigDecimal("winning_bid")).thenReturn(new BigDecimal("250.00"));

            AuctionFinalizer.finalizeIfEnded(conn, AUCTION_ID);

            assertTrue(preparedSql().stream().anyMatch(s -> s.startsWith("INSERT INTO orders")),
                    "a concluded blind auction must produce an order for the winner");
            ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
            verify(ps).setBigDecimal(eq(4), amount.capture());
            assertEquals(new BigDecimal("250.00"), amount.getValue());
            verify(ps).setInt(2, OTHER_BIDDER);
        }

        @Test
        @DisplayName("A blind auction nobody bid on ends with no winner and no order")
        void noSealedBidsMeansNoWinner() throws Exception {
            when(rs.next()).thenReturn(true, false);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));

            AuctionFinalizer.FinalizeResult r = AuctionFinalizer.finalizeIfEnded(conn, AUCTION_ID);

            assertTrue(r.finalized);
            assertEquals(-1, r.winnerId);
            verify(ps, never()).setBigDecimal(eq(2), any());
        }

        @Test
        @DisplayName("The winner is told they won and every losing bidder is told it closed")
        void theWinnerAndEveryLoserAreTold() throws Exception {
            stubExpiredAuctionWithTopBid(OTHER_BIDDER, "250.00");

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class);
                 MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                db.when(() -> DBUtil.runInTransaction(any())).thenCallRealMethod();

                AuctionFinalizer.finalizeIfExpiredAndNotify(AUCTION_ID);

                notifications.verify(
                        () -> NotificationService.notifyAuctionWonIfAbsent(AUCTION_ID, OTHER_BIDDER));
                notifications.verify(
                        () -> NotificationService.notifyAuctionLost(AUCTION_ID, OTHER_BIDDER));
            }
        }

        @Test
        @DisplayName("An unsold blind auction tells the seller, and names no winner to anyone")
        void anUnsoldBlindAuctionNamesNoWinner() throws Exception {
            when(rs.next()).thenReturn(true, false);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class);
                 MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                db.when(() -> DBUtil.runInTransaction(any())).thenCallRealMethod();

                AuctionFinalizer.finalizeIfExpiredAndNotify(AUCTION_ID);

                notifications.verify(() -> NotificationService.notifyAuctionEndedUnsold(AUCTION_ID));
                notifications.verify(
                        () -> NotificationService.notifyAuctionWonIfAbsent(anyLong(), anyInt()), never());
            }
        }
    }

    // =========================================================================
    // Regressions for the five leaks the confidentiality audit found
    // =========================================================================

    /**
     * The read paths that reached a live blind auction's leading bid without a guard.
     *
     * <p>Each of these failed against the code as it stood before the audit. They are kept
     * separate from the tests above because they exist to stop a specific defect coming
     * back, not to describe the mechanism.</p>
     */
    @Nested
    @DisplayName("Confidentiality regressions")
    class ConfidentialityRegressions {

        private Connection conn;
        private PreparedStatement ps;
        private ResultSet rs;

        @BeforeEach
        void setUp() throws Exception {
            conn = mock(Connection.class);
            ps   = mock(PreparedStatement.class);
            rs   = mock(ResultSet.class);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(false);
        }

        private List<String> preparedSql() throws Exception {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn, atLeastOnce()).prepareStatement(sql.capture());
            return sql.getAllValues();
        }

        /** Every query that computes a price from the bids table must except a live blind row. */
        private void assertEveryComputedPriceGuardsBlind() throws Exception {
            List<String> computed = preparedSql().stream()
                    .filter(s -> s.contains("MAX(b.bid_amount)"))
                    .collect(Collectors.toList());

            assertFalse(computed.isEmpty(), "expected a query computing a price from the bids table");
            for (String sql : computed) {
                assertTrue(sql.contains("a.auction_type = " + AuctionType.BLIND.getId()),
                        "this price is computed straight off the bids table, so a live blind "
                                + "auction's leading bid goes out with it: " + sql);
            }
        }

        @Test
        @DisplayName("D1 — the watchlist does not hand a watcher the leading sealed bid")
        void watchlistDoesNotLeakTheSealedBid() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new WatchlistDAO().listByUser(BIDDER);
            }
            assertEveryComputedPriceGuardsBlind();
        }

        @Test
        @DisplayName("D2 — the public seller profile does not expose it to visitors with no session")
        void sellerProfileDoesNotLeakTheSealedBid() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new SellerProfileDAO().getActiveListings(SELLER, 48);
            }
            assertEveryComputedPriceGuardsBlind();
        }

        @Test
        @DisplayName("D3 — the bid history DAO returns no rows for a live blind auction, whoever asks")
        void bidHistoryHidesLiveSealedBidsForEveryCaller() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                // Both overloads: the legacy JSP servlets take the three-argument one, which
                // had no guard of its own and no servlet in front of it that supplied one.
                new BidDAO().getBidHistory(AUCTION_ID, 1, 10);
                new BidDAO().getBidHistory(AUCTION_ID, 1, 10, BIDDER);
            }

            List<String> historyQueries = preparedSql().stream()
                    .filter(s -> s.contains("FROM bids b"))
                    .collect(Collectors.toList());

            assertEquals(2, historyQueries.size(), "expected both bid-history overloads to run");
            for (String sql : historyQueries) {
                assertTrue(sql.contains("a.auction_type = " + AuctionType.BLIND.getId())
                                && sql.contains("NOT EXISTS"),
                        "a live blind auction's bids are served in full by: " + sql);
            }
        }

        @Test
        @DisplayName("D4 — an ascending bid on a blind auction is refused, not answered with a price hint")
        void ascendingBidOnABlindAuctionIsRefused() throws Exception {
            // The legacy /protected/bid servlet calls placeBid for every auction type. Left
            // unguarded it compared the bid against MAX(bid_amount), so BID_TOO_LOW answered
            // "is the sealed top bid above this?" for any amount the caller cared to try.
            when(rs.next()).thenReturn(true);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
            when(rs.getString("moderation_state")).thenReturn("active");
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getInt("auction_type")).thenReturn(AuctionType.BLIND.getId());
            when(rs.getBigDecimal("starting_price")).thenReturn(new BigDecimal("100.00"));

            BidDAO.BidOutcome outcome;
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                outcome = new BidDAO().placeBid(AUCTION_ID, BIDDER, new BigDecimal("250.00"));
            }

            assertEquals(BidDAO.BidResult.WRONG_AUCTION_TYPE, outcome.result);
            assertFalse(outcome.isSuccess());
            verify(conn).rollback();
            verify(conn, never()).commit();
            assertFalse(preparedSql().stream().anyMatch(s -> s.startsWith("INSERT INTO bids")),
                        "no bid may be written to a sealed auction by the ascending path");
        }

        @Test
        @DisplayName("D5 — the price filter's result count cannot be used to probe the sealed bid")
        void thePriceFilteredCountCannotProbeTheSealedBid() throws Exception {
            // The result page already resolved a blind row to its entry price, but the count
            // behind it did not, so narrowing minPrice/maxPrice and watching the total move
            // located the sealed bid without it ever appearing on screen.
            SearchFilter filter = SearchFilter.builder()
                    .minPrice(new BigDecimal("200"))
                    .maxPrice(new BigDecimal("300"))
                    .build();

            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new SearchDAO().count("guitar", null, filter);
            }
            assertEveryComputedPriceGuardsBlind();
        }

        @Test
        @DisplayName("A concluded blind auction is not hidden — the guard expires with the auction")
        void aConcludedBlindAuctionIsNotHidden() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                new WatchlistDAO().listByUser(BIDDER);
                new BidDAO().getBidHistory(AUCTION_ID, 1, 10);
            }

            for (String sql : preparedSql()) {
                if (sql.contains("a.auction_type = " + AuctionType.BLIND.getId())) {
                    assertTrue(sql.contains("a.date_end > CURRENT_TIMESTAMP"),
                            "hiding must be conditional on the auction still running, otherwise a "
                                    + "concluded sealed auction never reveals its winning bid: " + sql);
                }
            }
        }
    }

    // =========================================================================
    // Auto-bid does not apply to a sealed auction
    // =========================================================================

    @Nested
    @DisplayName("Auto-bid does not apply to a blind auction")
    class AutoBidDoesNotApply {

        private AutoBidDAO autoBidDAO;
        private Wrapper servlet;
        private HttpServletRequest req;
        private HttpServletResponse resp;

        private class Wrapper extends AutoBidApiServlet {
            @Override public void doPost(HttpServletRequest r, HttpServletResponse s)
                    throws java.io.IOException { super.doPost(r, s); }
            @Override public void doGet(HttpServletRequest r, HttpServletResponse s)
                    throws java.io.IOException { super.doGet(r, s); }
        }

        @BeforeEach
        void setUp() {
            autoBidDAO = mock(AutoBidDAO.class);
            servlet = new Wrapper();
            servlet.setAutoBidDAO(autoBidDAO);
            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);

            ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(BIDDER));
            when(req.getParameter("auctionId")).thenReturn(String.valueOf(AUCTION_ID));
            when(autoBidDAO.isBlindAuction(AUCTION_ID)).thenReturn(true);
        }

        @Test
        @DisplayName("Setting an auto-bid on one is refused with an explanation, not stored")
        void settingAnAutoBidIsRefused() throws Exception {
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("500");

            StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(400);
            verify(autoBidDAO, never()).upsert(anyLong(), anyInt(), any(), any(), any());
            String error = ApiTestSupport.parse(sw).get("error").asText();
            assertTrue(error.contains("sealed-bid"), "the message should say why, not just refuse");
        }

        @Test
        @DisplayName("Cancelling one is still allowed, so a row predating the guard can be cleared")
        void cancellingIsStillAllowed() throws Exception {
            when(req.getParameter("action")).thenReturn("CANCEL");

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(autoBidDAO).delete(AUCTION_ID, BIDDER);
        }

        @Test
        @DisplayName("Reading one back reports none, whatever is still stored against the auction")
        void readingOneBackReportsNone() throws Exception {
            ApiTestSupport.bindJsonWriter(resp);
            servlet.doGet(req, resp);

            verify(resp).setStatus(404);
            verify(autoBidDAO, never()).getAutoBidForUser(anyLong(), anyInt());
        }

        @Test
        @DisplayName("An ascending auction still accepts an auto-bid — the guard is type-specific")
        void anAscendingAuctionStillAcceptsOne() throws Exception {
            when(autoBidDAO.isBlindAuction(AUCTION_ID)).thenReturn(false);
            when(req.getParameter("action")).thenReturn("SET");
            when(req.getParameter("maxAmount")).thenReturn("500");

            ApiTestSupport.bindJsonWriter(resp);
            servlet.doPost(req, resp);

            verify(resp).setStatus(200);
            verify(autoBidDAO).upsert(eq(AUCTION_ID), eq(BIDDER), eq(new BigDecimal("500")),
                    any(), any());
        }
    }
}
