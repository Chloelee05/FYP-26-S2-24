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
 * GET  /api/notifications             — recent notifications + unread count for the user
 * POST /api/notifications   action=read [id] | readAll
 * GET  /api/notifications/preferences — the user's notification preferences
 * POST /api/notifications/preferences — save preferences (params: outbid, endingSoon, wonAuction,
 *      plus optional telegramEnabled / telegramOutbid / telegramWon / telegramLost /
 *      telegramSellerResult / telegramSellerPrice / telegramOrderUpdates)
 * Requires any authenticated user.
 *
 * <p>Two related jobs behind one servlet: the in-app notification bell, and the per-user
 * switches that decide which events produce a message. Everything is scoped to
 * {@code sessionUserId}, so a caller can only read and mark their own notifications.</p>
 *
 * <p>The Telegram switches are handled defensively. They are only written when the request
 * actually carries them, and reading them falls back to defaults on error, so the in-app
 * toggles keep working against a database that has not had the Telegram migration applied.</p>
 */
@WebServlet({"/api/notifications", "/api/notifications/preferences"})
public class NotificationApiServlet extends ApiBase {

    /** The bell only shows recent items, so the list is capped rather than paged. */
    private static final int LIST_LIMIT = 30;
    private NotificationDAO dao;

    public NotificationApiServlet() {
        this.dao = new NotificationDAO();
    }

    /** Test hook: lets a unit test supply a stub DAO. */
    public void setNotificationDAO(NotificationDAO dao) { this.dao = dao; }

    /**
     * Both URLs map to this one servlet, so the sub-path is what separates the notification list
     * from the preferences form.
     */
    private static boolean isPreferencesPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri != null && uri.endsWith("/preferences");
    }

    /**
     * GET on either mapped path. At /preferences it returns the in-app switches with the
     * Telegram ones nested under {@code telegram}. Otherwise it returns the most recent
     * notifications plus {@code unreadCount}, which is the number on the bell.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        if (isPreferencesPath(req)) {
            NotificationDAO.Preferences p = dao.getUserPreferences(userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("outbid", p.outbid);
            body.put("endingSoon", p.endingSoon);
            body.put("wonAuction", p.wonAuction);
            body.put("telegram", telegramBody(userId));
            ok(resp, body);
            return;
        }

        List<Notification> items = dao.listForUser(userId, LIST_LIMIT);
        int unread = dao.countUnread(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", items);
        body.put("unreadCount", unread);
        ok(resp, body);
    }

    /**
     * POST on either mapped path. At /preferences it saves the switches, defaulting any field
     * the form did not send to its stored value. Otherwise {@code action=readAll} clears the
     * whole bell and the default {@code action=read} with an {@code id} clears one item.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        if (isPreferencesPath(req)) {
            // Read the stored values first so an absent parameter keeps its current setting.
            NotificationDAO.Preferences current = dao.getUserPreferences(userId);
            boolean outbid     = boolParam(req, "outbid", current.outbid);
            boolean endingSoon = boolParam(req, "endingSoon", current.endingSoon);
            boolean won        = boolParam(req, "wonAuction", current.wonAuction);
            try {
                dao.saveUserPreferences(userId, outbid, endingSoon, won);
                // Only written when the caller actually sent Telegram fields, so the in-app
                // toggles keep working unchanged against a database without those columns.
                if (hasTelegramParams(req)) {
                    dao.saveTelegramPreferences(userId, telegramFromRequest(req, userId));
                }
            } catch (Exception e) {
                serverError(resp, "Failed to save notification preferences.");
                return;
            }
            okMsg(resp, "Notification preferences saved.");
            return;
        }

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

        // markRead is scoped by userId, so passing another member's notification id changes nothing.
        dao.markRead(userId, id);
        okMsg(resp, "Notification marked read.");
    }

    /** Telegram preferences as a nested object, or defaults if the columns are missing. */
    private Map<String, Object> telegramBody(int userId) {
        NotificationDAO.TelegramPreferences t;
        try {
            t = dao.getTelegramPreferences(userId);
        } catch (RuntimeException e) {
            t = NotificationDAO.TelegramPreferences.defaults();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", t.enabled);
        body.put("outbid", t.outbid);
        body.put("won", t.won);
        body.put("lost", t.lost);
        body.put("sellerResult", t.sellerResult);
        body.put("sellerPrice", t.sellerPrice);
        body.put("orderUpdates", t.orderUpdates);
        return body;
    }

    private static final String[] TELEGRAM_PARAMS = {
        "telegramEnabled", "telegramOutbid", "telegramWon",
        "telegramLost", "telegramSellerResult", "telegramSellerPrice",
        "telegramOrderUpdates",
    };

    /** True when the request carries at least one Telegram field, which is what gates the write above. */
    private boolean hasTelegramParams(HttpServletRequest req) {
        for (String name : TELEGRAM_PARAMS) {
            if (req.getParameter(name) != null) return true;
        }
        return false;
    }

    /** Missing fields keep their stored value, so a partial update never silently resets a switch. */
    private NotificationDAO.TelegramPreferences telegramFromRequest(HttpServletRequest req, int userId) {
        NotificationDAO.TelegramPreferences current;
        try {
            current = dao.getTelegramPreferences(userId);
        } catch (RuntimeException e) {
            current = NotificationDAO.TelegramPreferences.defaults();
        }
        return new NotificationDAO.TelegramPreferences(
                boolParam(req, "telegramEnabled", current.enabled),
                boolParam(req, "telegramOutbid", current.outbid),
                boolParam(req, "telegramWon", current.won),
                boolParam(req, "telegramLost", current.lost),
                boolParam(req, "telegramSellerResult", current.sellerResult),
                boolParam(req, "telegramSellerPrice", current.sellerPrice),
                boolParam(req, "telegramOrderUpdates", current.orderUpdates));
    }

    /**
     * Reads a checkbox-style parameter, accepting "true", "1" or "on". An absent parameter
     * returns {@code fallback}, which is how a partial form submission avoids clearing switches.
     */
    private boolean boolParam(HttpServletRequest req, String name, boolean fallback) {
        String v = param(req, name);
        if (v == null) return fallback;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }
}
