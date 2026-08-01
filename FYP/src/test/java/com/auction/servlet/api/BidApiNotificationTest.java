package com.auction.servlet.api;

import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidOutcome;
import com.auction.dao.BidDAO.BidResult;
import com.auction.model.AuctionType;
import com.auction.notification.NotificationService;
import com.auction.realtime.AuctionEventPublisher;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Which notifications {@link BidApiServlet} sends, and to whom.
 *
 * <p>Covers the corrected OUTBID recipient — the servlet must forward the bidder the DAO says
 * was displaced, not the previous leader — and the LOST fan-out on the two ways a bid can end
 * an auction outright.</p>
 */
@DisplayName("BidApiServlet — notifications")
class BidApiNotificationTest {

    private static final long AUCTION_ID = 10L;
    private static final int BUYER = 5;
    private static final int PREVIOUS_LEADER = 3;
    private static final int AUTO_BIDDER = 8;

    private static class Wrapper extends BidApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private BidDAO bidDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() throws Exception {
        bidDAO = mock(BidDAO.class);
        servlet = new Wrapper();
        servlet.setBidDAO(bidDAO);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);

        AuthSession session = ApiTestSupport.newBuyerSession(BUYER);
        ApiTestSupport.withBearer(req, session);
        ApiTestSupport.bindJsonWriter(resp);
        when(req.getParameter("auctionId")).thenReturn(String.valueOf(AUCTION_ID));
    }

    /** {@code BidOutcome}'s full constructor is package-private to the DAO; tests build via it. */
    private static BidOutcome outcome(Integer previousTop, Integer finalTop) {
        try {
            Constructor<BidOutcome> c = BidOutcome.class.getDeclaredConstructor(
                    BidResult.class, Integer.class, Integer.class, int.class);
            c.setAccessible(true);
            return c.newInstance(BidResult.SUCCESS, previousTop, finalTop, BUYER);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface Assertions {
        void check(MockedStatic<NotificationService> notifications) throws Exception;
    }

    private void post(Assertions assertions) throws Exception {
        try (MockedStatic<AuctionEventPublisher> ignored = mockStatic(AuctionEventPublisher.class);
             MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
            servlet.doPost(req, resp);
            assertions.check(notifications);
        }
    }

    @Nested
    @DisplayName("Ascending bid — the OUTBID recipient")
    class Ascending {

        @BeforeEach
        void ascending() {
            when(req.getParameter("bidAmount")).thenReturn("100");
            when(bidDAO.getAuctionTypeId(AUCTION_ID)).thenReturn(AuctionType.PRICE_UP.getId());
        }

        @Test
        @DisplayName("A plain bid tells the previous leader")
        void plainBidNotifiesThePreviousLeader() throws Exception {
            when(bidDAO.placeBid(eq(AUCTION_ID), eq(BUYER), any()))
                    .thenReturn(outcome(PREVIOUS_LEADER, BUYER));

            post(notifications -> notifications.verify(
                    () -> NotificationService.notifyOutbid(AUCTION_ID, PREVIOUS_LEADER)));
        }

        @Test
        @DisplayName("When a proxy auto-bid counter-bids, the buyer is told — not the auto-bidder now winning")
        void counterBidNotifiesTheBuyer() throws Exception {
            when(bidDAO.placeBid(eq(AUCTION_ID), eq(BUYER), any()))
                    .thenReturn(outcome(AUTO_BIDDER, AUTO_BIDDER));

            post(notifications -> {
                notifications.verify(() -> NotificationService.notifyOutbid(AUCTION_ID, BUYER));
                notifications.verify(
                        () -> NotificationService.notifyOutbid(AUCTION_ID, AUTO_BIDDER), never());
            });
        }

        @Test
        @DisplayName("The first bid on an auction notifies no buyer, only the seller")
        void firstBidNotifiesOnlyTheSeller() throws Exception {
            when(bidDAO.placeBid(eq(AUCTION_ID), eq(BUYER), any()))
                    .thenReturn(outcome(null, BUYER));

            post(notifications -> {
                notifications.verify(
                        () -> NotificationService.notifyOutbid(anyLong(), anyInt()), never());
                notifications.verify(() -> NotificationService.notifySellerNewBid(
                        eq(AUCTION_ID), any(BigDecimal.class)));
            });
        }

        @Test
        @DisplayName("A rejected bid notifies nobody")
        void rejectionNotifiesNobody() throws Exception {
            when(bidDAO.placeBid(eq(AUCTION_ID), eq(BUYER), any()))
                    .thenReturn(BidOutcome.of(BidResult.BID_TOO_LOW));

            post(notifications -> notifications.verifyNoInteractions());
        }
    }

    @Nested
    @DisplayName("Concluding an auction outright")
    class Concluding {

        @Test
        @DisplayName("Buy It Now tells the winner and every other bidder that it closed")
        void buyItNowFansOutLost() throws Exception {
            when(req.getParameter("action")).thenReturn("BUY_NOW");
            when(bidDAO.buyItNow(AUCTION_ID, BUYER)).thenReturn(BidResult.SUCCESS);

            post(notifications -> {
                notifications.verify(() -> NotificationService.notifyAuctionWon(AUCTION_ID, BUYER));
                notifications.verify(() -> NotificationService.notifyAuctionLost(AUCTION_ID, BUYER));
            });
        }

        @Test
        @DisplayName("A Dutch acceptance does the same for everyone still watching the clock")
        void dutchAcceptanceFansOutLost() throws Exception {
            when(bidDAO.getAuctionTypeId(AUCTION_ID)).thenReturn(AuctionType.DUTCH_AUCTION.getId());
            when(bidDAO.acceptDutchBid(AUCTION_ID, BUYER)).thenReturn(BidResult.SUCCESS);

            post(notifications -> {
                notifications.verify(() -> NotificationService.notifyAuctionWon(AUCTION_ID, BUYER));
                notifications.verify(() -> NotificationService.notifyAuctionLost(AUCTION_ID, BUYER));
            });
        }

        @Test
        @DisplayName("A failed Buy It Now concludes nothing and notifies nobody")
        void failedBuyItNowNotifiesNobody() throws Exception {
            when(req.getParameter("action")).thenReturn("BUY_NOW");
            when(bidDAO.buyItNow(AUCTION_ID, BUYER)).thenReturn(BidResult.AUCTION_CLOSED);

            post(notifications -> notifications.verifyNoInteractions());
        }
    }
}
