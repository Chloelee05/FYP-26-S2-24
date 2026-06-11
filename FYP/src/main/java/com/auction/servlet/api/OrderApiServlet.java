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
 * POST /api/orders/complete      — (buyer) confirm receipt after delivery
 * POST /api/orders/refund        — (buyer) request a refund on a paid order
 * POST /api/orders/refund-resolve— (seller) approve/decline a refund request
 */
@WebServlet("/api/orders/*")
public class OrderApiServlet extends ApiBase {

    private OrderDAO orderDAO;
    private PaymentMethodDAO paymentDAO;

    public OrderApiServlet() {
        this.orderDAO   = new OrderDAO();
        this.paymentDAO = new PaymentMethodDAO();
    }

    /** Test hook */
    public void setOrderDAO(OrderDAO orderDAO)             { this.orderDAO   = orderDAO; }
    public void setPaymentMethodDAO(PaymentMethodDAO pm)   { this.paymentDAO = pm; }

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
                case "shipping": handleShipping(req, resp, userId); break;
                case "refund":   handleRefund(req, resp, userId); break;
                case "refund-resolve": handleRefundResolve(req, resp, userId); break;
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

        boolean early = isTruthy(req, "early");
        DeclareResult r = orderDAO.declareWinner(auctionId, sellerId, early);
        if (r.status == DeclareStatus.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            NotificationService.notifyAuctionWon(auctionId, r.winnerId);
            okMsg(resp, early ? "Winner declared early and order created." : "Winner declared and order created.");
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

    private void handleComplete(HttpServletRequest req, HttpServletResponse resp, int buyerId) throws IOException {
        if (!"BUYER".equalsIgnoreCase(sessionRole(req))) { forbidden(resp); return; }
        Long orderId = parseLong(param(req, "orderId"));
        if (orderId == null) { badRequest(resp, "orderId is required."); return; }

        boolean ok = orderDAO.confirmReceipt(orderId, buyerId);
        if (!ok) {
            badRequest(resp, "Order not found. Confirm receipt only after the seller marks it delivered.");
            return;
        }

        int[] parties = orderDAO.partiesAndAuction(orderId);
        if (parties != null) NotificationService.notifySellerReceiptConfirmed(parties[2], parties[1]);
        okMsg(resp, "Receipt confirmed. You can now rate the seller.");
    }

    private void handleRefund(HttpServletRequest req, HttpServletResponse resp, int buyerId) throws IOException {
        if (!"BUYER".equalsIgnoreCase(sessionRole(req))) { forbidden(resp); return; }
        Long orderId = parseLong(param(req, "orderId"));
        if (orderId == null) { badRequest(resp, "orderId is required."); return; }
        String reason = param(req, "reason");
        if (reason == null || reason.length() < 10) {
            badRequest(resp, "Please describe the issue (at least 10 characters).");
            return;
        }

        OrderDAO.RefundResult r = orderDAO.requestRefund(orderId, buyerId, reason);
        switch (r) {
            case SUCCESS:
                int[] parties = orderDAO.partiesAndAuction(orderId);
                if (parties != null) NotificationService.notifySellerRefundRequested(parties[2], parties[1]);
                okMsg(resp, "Refund request sent to the seller. They will approve or decline it.");
                break;
            case NOT_FOUND:
                error(resp, 404, "Order not found.");
                break;
            case NOT_BUYER:
                forbidden(resp);
                break;
            case ALREADY_REQUESTED:
                error(resp, 400, "A refund has already been requested for this order.");
                break;
            case NOT_ELIGIBLE:
            default:
                error(resp, 400, "Refunds can only be requested on paid orders that are not yet completed.");
        }
    }

    private void handleRefundResolve(HttpServletRequest req, HttpServletResponse resp, int sellerId) throws IOException {
        if (!"SELLER".equalsIgnoreCase(sessionRole(req))) { forbidden(resp); return; }
        Long orderId = parseLong(param(req, "orderId"));
        if (orderId == null) { badRequest(resp, "orderId is required."); return; }
        String action = param(req, "action");
        boolean approve = "approve".equalsIgnoreCase(action);
        if (!approve && !"reject".equalsIgnoreCase(action)) {
            badRequest(resp, "action must be 'approve' or 'reject'."); return;
        }

        OrderDAO.RefundDecision d = orderDAO.resolveRefund(orderId, sellerId, approve);
        switch (d) {
            case SUCCESS:
                int[] parties = orderDAO.partiesAndAuction(orderId);
                if (parties != null) NotificationService.notifyBuyerRefundResolved(parties[2], parties[0], approve);
                okMsg(resp, approve
                        ? "Refund approved. The order was cancelled and the buyer notified."
                        : "Refund request declined. The buyer has been notified.");
                break;
            case NOT_FOUND:
                error(resp, 404, "Order not found.");
                break;
            case NOT_REQUESTED:
            default:
                error(resp, 400, "There is no pending refund request for this order.");
        }
    }

    private void handleShipping(HttpServletRequest req, HttpServletResponse resp, int sellerId) throws IOException {
        if (!"SELLER".equalsIgnoreCase(sessionRole(req))) { forbidden(resp); return; }
        Long orderId = parseLong(param(req, "orderId"));
        if (orderId == null) { badRequest(resp, "orderId is required."); return; }
        OrderDAO.ShippingAdvanceResult r = orderDAO.advanceShipping(orderId, sellerId);
        switch (r) {
            case SUCCESS:
                notifyIfDelivered(orderId);
                okMsg(resp, "Shipping status updated.");
                break;
            case NOT_FOUND:
                error(resp, 404, "Order not found.");
                break;
            case NOT_SELLER:
                forbidden(resp);
                break;
            case NOT_PAID:
                error(resp, 400, "Order must be paid before updating shipping.");
                break;
            case ALREADY_DELIVERED:
                error(resp, 400, "Package is already marked delivered.");
                break;
            default:
                error(resp, 400, "Could not update shipping.");
        }
    }

    private void notifyIfDelivered(long orderId) {
        int[] parties = orderDAO.partiesAndAuction(orderId);
        if (parties == null) return;
        if (orderDAO.isDelivered(orderId)) {
            NotificationService.notifyOrderDelivered(parties[2], parties[0]);
        }
    }

    private String declareMessage(DeclareStatus s) {
        switch (s) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case NOT_SELLER:         return "You do not own this auction.";
            case NOT_ENDED:          return "This auction has not ended yet. Use early=true to close now.";
            case NOT_ACTIVE:         return "This auction is not active.";
            case ALREADY_FINALIZED:  return "This auction has already been finalised.";
            case NO_BIDS:            return "This auction received no bids.";
            default:                 return "Could not declare a winner.";
        }
    }

    private static boolean isTruthy(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        if (v == null || v.isBlank()) return false;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim());
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
