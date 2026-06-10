package com.auction.servlet.api;

import com.auction.dao.OrderDAO;
import com.auction.dao.OrderDAO.DeclareResult;
import com.auction.dao.OrderDAO.DeclareStatus;
import com.auction.dao.PaymentMethodDAO;
import com.auction.notification.NotificationService;
import com.auction.realtime.AuctionEventPublisher;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Orders — simulated post-auction checkout.
 * GET  /api/orders               — orders where the user is buyer or seller
 * POST /api/orders/declare       — (seller) finalise an ended auction → create order
 * POST /api/orders/pay           — (buyer) simulate payment (params: orderId, paymentMethodId?)
 * POST /api/orders/complete      — (seller) mark fulfilled
 */
@WebServlet("/api/orders/*")
public class OrderApiServlet extends ApiBase {

    private final OrderDAO orderDAO = new OrderDAO();
    private final PaymentMethodDAO paymentDAO = new PaymentMethodDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        ok(resp, orderDAO.listForUser(sessionUserId(req)));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String sub = sub(req);
        try {
            switch (sub) {
                case "declare":  handleDeclare(req, resp, userId); break;
                case "pay":      handlePay(req, resp, userId); break;
                case "complete": handleComplete(req, resp, userId); break;
                default: error(resp, 404, "Unknown order endpoint"); break;
            }
        } catch (RuntimeException e) {
            getServletContext().log("order error", e);
            serverError(resp, "Order operation failed. Run DB migrations and try again.");
        }
    }

    private void handleDeclare(HttpServletRequest req, HttpServletResponse resp, int sellerId) throws IOException {
        if (!"SELLER".equalsIgnoreCase(sessionRole(req))) { forbidden(resp); return; }
        Long auctionId = parseLong(param(req, "auctionId"));
        if (auctionId == null) { badRequest(resp, "auctionId is required."); return; }

        DeclareResult r = orderDAO.declareWinner(auctionId, sellerId);
        if (r.status == DeclareStatus.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            NotificationService.notifyAuctionWon(auctionId, r.winnerId);
            okMsg(resp, "Winner declared and order created.");
        } else {
            error(resp, 400, declareMessage(r.status));
        }
    }

    private void handlePay(HttpServletRequest req, HttpServletResponse resp, int buyerId) throws IOException {
        Long orderId = parseLong(param(req, "orderId"));
        if (orderId == null) { badRequest(resp, "orderId is required."); return; }

        Long pmId = parseLong(param(req, "paymentMethodId"));
        if (pmId != null && !paymentDAO.belongsTo(buyerId, pmId)) {
            badRequest(resp, "Invalid payment method."); return;
        }

        boolean ok = orderDAO.pay(orderId, buyerId, pmId);
        if (!ok) { badRequest(resp, "Order not found or not awaiting payment."); return; }

        int[] parties = orderDAO.partiesAndAuction(orderId);
        if (parties != null) NotificationService.notifyOrderPaid(parties[2], parties[1]);
        okMsg(resp, "Payment successful. The seller has been notified.");
    }

    private void handleComplete(HttpServletRequest req, HttpServletResponse resp, int sellerId) throws IOException {
        Long orderId = parseLong(param(req, "orderId"));
        if (orderId == null) { badRequest(resp, "orderId is required."); return; }

        boolean ok = orderDAO.complete(orderId, sellerId);
        if (!ok) { badRequest(resp, "Order not found or not yet paid."); return; }

        int[] parties = orderDAO.partiesAndAuction(orderId);
        if (parties != null) NotificationService.notifyOrderCompleted(parties[2], parties[0]);
        okMsg(resp, "Order marked complete.");
    }

    private String declareMessage(DeclareStatus s) {
        switch (s) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case NOT_SELLER:         return "You do not own this auction.";
            case NOT_ENDED:          return "This auction has not ended yet.";
            case ALREADY_FINALIZED:  return "This auction has already been finalised.";
            case NO_BIDS:            return "This auction received no bids.";
            default:                 return "Could not declare a winner.";
        }
    }

    private Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private String sub(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return "";
        return p.replaceFirst("^/", "").split("/")[0];
    }
}
