package com.auction.servlet.api;

import com.auction.dao.NotificationDAO;
import com.auction.model.Notification;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET  /api/notifications            — recent notifications + unread count for the user
 * POST /api/notifications  action=read [id] | readAll
 * Requires any authenticated user.
 */
@WebServlet("/api/notifications")
public class NotificationApiServlet extends ApiBase {

    private static final int LIST_LIMIT = 30;
    private NotificationDAO dao;

    public NotificationApiServlet() {
        this.dao = new NotificationDAO();
    }

    /** Test hook */
    public void setNotificationDAO(NotificationDAO dao) { this.dao = dao; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        List<Notification> items = dao.listForUser(userId, LIST_LIMIT);
        int unread = dao.countUnread(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", items);
        body.put("unreadCount", unread);
        ok(resp, body);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String action = param(req, "action");
        if (action == null) action = "read";

        if ("readAll".equalsIgnoreCase(action)) {
            int n = dao.markAllRead(userId);
            okMsg(resp, "Marked " + n + " notification(s) read.");
            return;
        }

        String idStr = param(req, "id");
        if (idStr == null) { badRequest(resp, "id is required."); return; }
        long id;
        try { id = Long.parseLong(idStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid notification id."); return; }

        dao.markRead(userId, id);
        okMsg(resp, "Notification marked read.");
    }
}
