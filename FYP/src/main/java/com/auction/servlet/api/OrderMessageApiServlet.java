package com.auction.servlet.api;

import com.auction.dao.OrderDAO;
import com.auction.dao.OrderMessageDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.notification.NotificationService;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Direct buyer&lt;-&gt;seller messaging tied to an order (separate from admin support).
 *
 * GET  /api/order-messages            — conversations for the current user
 * GET  /api/order-messages/{orderId}  — messages for one order (buyer or seller only)
 * POST /api/order-messages/{orderId}  — send a message (params: body)
 */
@WebServlet("/api/order-messages/*")
public class OrderMessageApiServlet extends ApiBase {

    private final OrderMessageDAO messageDAO = new OrderMessageDAO();
    private final OrderDAO orderDAO = new OrderDAO();
    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);
        Long orderId = pathId(req);
        try {
            if (orderId == null) {
                ok(resp, messageDAO.listConversations(userId));
                return;
            }
            if (!messageDAO.isParticipant(orderId, userId)) { forbidden(resp); return; }
            ok(resp, messageDAO.listMessages(orderId));
        } catch (RuntimeException e) {
            getServletContext().log("order-message GET error", e);
            serverError(resp, "Could not load messages. Run DB migrations and try again.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);
        Long orderId = pathId(req);
        if (orderId == null) { badRequest(resp, "Order id is required in the path."); return; }

        try {
            if (!messageDAO.isParticipant(orderId, userId)) { forbidden(resp); return; }

            String body = SecurityUtil.sanitize(param(req, "body"));
            if (body == null || body.isBlank()) { badRequest(resp, "Message body is required."); return; }
            if (body.length() > 2000) body = body.substring(0, 2000);

            long msgId = messageDAO.addMessage(orderId, userId, body);
            if (msgId < 0) { serverError(resp, "Could not send message."); return; }

            // Notify the other party.
            int[] parties = orderDAO.partiesAndAuction(orderId); // [buyer, seller, auction]
            if (parties != null) {
                int recipient = (parties[0] == userId) ? parties[1] : parties[0];
                String senderName = senderName(userId);
                NotificationService.notifyOrderMessage(parties[2], recipient, senderName);
            }
            okMsg(resp, "Message sent.");
        } catch (RuntimeException e) {
            getServletContext().log("order-message POST error", e);
            serverError(resp, "Could not send message. Run DB migrations and try again.");
        }
    }

    private String senderName(int userId) {
        try {
            User u = userDAO.getUserById(userId);
            return u != null ? u.getUsername() : "A user";
        } catch (Exception e) {
            return "A user";
        }
    }

    /** Parses the leading path segment as an order id, or null when absent. */
    private Long pathId(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return null;
        String first = p.replaceFirst("^/", "").split("/")[0];
        if (first.isBlank()) return null;
        try { return Long.parseLong(first); } catch (NumberFormatException e) { return null; }
    }
}
