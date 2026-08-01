package com.auction.servlet.api;

import com.auction.dao.AutoBidDAO;
import com.auction.notification.NotificationService;
import com.auction.realtime.AuctionEventPublisher;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import com.auction.util.DBUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Setting an auto-bid that immediately takes the lead used to notify nobody at all: this path
 * inserts into {@code bids} through {@code processAutoBids} without ever going near
 * {@code placeBid}, and never called {@code notifyOutbid}. These cover the missing notification.
 */
@DisplayName("AutoBidApiServlet — notifying the bidder an auto-bid displaces")
class AutoBidOutbidNotificationTest {

    private static final long AUCTION_ID = 42L;
    private static final int BUYER = 5;
    private static final int PREVIOUS_LEADER = 3;

    private static class Wrapper extends AutoBidApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private AutoBidDAO autoBidDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() throws Exception {
        autoBidDAO = mock(AutoBidDAO.class);
        servlet = new Wrapper();
        servlet.setAutoBidDAO(autoBidDAO);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);

        AuthSession session = ApiTestSupport.newBuyerSession(BUYER);
        ApiTestSupport.withBearer(req, session);
        ApiTestSupport.bindJsonWriter(resp);
        when(req.getParameter("auctionId")).thenReturn(String.valueOf(AUCTION_ID));
        when(req.getParameter("maxAmount")).thenReturn("500");
    }

    /**
     * A connection whose only query — "who holds the top bid" — answers {@code leaders} in
     * order, so a test can describe the lead changing hands across {@code processAutoBids}.
     */
    private static Connection connectionWithLeaders(Integer... leaders) throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = { -1 };
        when(rs.next()).thenAnswer(i -> ++cursor[0] < leaders.length && leaders[cursor[0]] != null);
        when(rs.getInt("user_id")).thenAnswer(i -> leaders[cursor[0]]);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        return conn;
    }

    /** Runs the servlet with the transaction executed against {@code conn} and pushes stubbed out. */
    private void post(Connection conn, ThrowingBody body) throws Exception {
        try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class);
             MockedStatic<AuctionEventPublisher> publisher = mockStatic(AuctionEventPublisher.class);
             MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
            db.when(() -> DBUtil.runInTransaction(any())).thenAnswer(i -> {
                DBUtil.TransactionBlock<?> block = i.getArgument(0);
                return block.execute(conn);
            });
            servlet.doPost(req, resp);
            body.run(notifications);
        }
    }

    @FunctionalInterface
    private interface ThrowingBody {
        void run(MockedStatic<NotificationService> notifications) throws Exception;
    }

    @Test
    @DisplayName("An auto-bid that takes the lead notifies the bidder it took it from")
    void displacedBidderIsNotified() throws Exception {
        Connection conn = connectionWithLeaders(PREVIOUS_LEADER, BUYER);

        post(conn, notifications ->
                notifications.verify(() -> NotificationService.notifyOutbid(AUCTION_ID, PREVIOUS_LEADER)));

        verify(autoBidDAO).processAutoBids(conn, AUCTION_ID);
    }

    @Test
    @DisplayName("An auto-bid too low to take the lead notifies nobody")
    void unchangedLeadNotifiesNobody() throws Exception {
        Connection conn = connectionWithLeaders(PREVIOUS_LEADER, PREVIOUS_LEADER);

        post(conn, notifications ->
                notifications.verify(() -> NotificationService.notifyOutbid(anyLong(), anyInt()), never()));
    }

    @Test
    @DisplayName("An auto-bid on an auction with no bids yet notifies nobody")
    void firstBidNotifiesNobody() throws Exception {
        Connection conn = connectionWithLeaders(null, BUYER);

        post(conn, notifications ->
                notifications.verify(() -> NotificationService.notifyOutbid(anyLong(), anyInt()), never()));
    }

    @Test
    @DisplayName("The auto-bid is still stored when the proxy round cannot run")
    void storageSurvivesAFailedProxyRound() throws Exception {
        try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class);
             MockedStatic<AuctionEventPublisher> ignored = mockStatic(AuctionEventPublisher.class);
             MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
            db.when(() -> DBUtil.runInTransaction(any())).thenThrow(new RuntimeException("no database"));

            servlet.doPost(req, resp);

            verify(autoBidDAO).upsert(eq(AUCTION_ID), eq(BUYER), any(), any(), any());
            verify(resp).setStatus(200);
            notifications.verify(() -> NotificationService.notifyOutbid(anyLong(), anyInt()), never());
        }
    }
}
