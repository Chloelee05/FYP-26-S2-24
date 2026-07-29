package com.auction.servlet.api;

import com.auction.dao.LandingContentDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GET  /api/admin/landing-content — every editable field with its form metadata.
 * POST /api/admin/landing-content — save values, or restore seeded defaults:
 * <ul>
 *   <li>{@code action=UPDATE} (default) with one parameter per content key,
 *       e.g. {@code hero.headline=Bid smart, buy}</li>
 *   <li>{@code action=RESET} with {@code key=<content key>} or {@code group=<group name>}</li>
 * </ul>
 * All require ADMIN role, checked here the same way {@code AdminApiServlet} does —
 * {@code AdminFilter} only covers the JSP {@code /admin/*} paths, not {@code /api/*}.
 *
 * <p>The exact mapping wins over {@code AdminApiServlet}'s {@code /api/admin/*} prefix
 * mapping, so this stays a separate servlet rather than another branch in that switch.</p>
 */
@WebServlet("/api/admin/landing-content")
public class AdminLandingContentApiServlet extends ApiBase {

    /** Long enough for the biggest paragraph on the page, short enough to bound abuse. */
    static final int MAX_VALUE_LENGTH = 2000;

    private LandingContentDAO landingContentDAO = new LandingContentDAO();

    /** Test hook */
    public void setLandingContentDAO(LandingContentDAO dao) { this.landingContentDAO = dao; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        try {
            ok(resp, landingContentDAO.listAll());
        } catch (Exception e) {
            serverError(resp, "Could not load landing page content. Run DB migrations and try again.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;

        String action = param(req, "action");
        if (action != null && action.equalsIgnoreCase("RESET")) {
            handleReset(req, resp);
            return;
        }
        if (action != null && !action.equalsIgnoreCase("UPDATE")) {
            badRequest(resp, "Unknown action: " + action);
            return;
        }
        handleUpdate(req, resp);
    }

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Set<String> allowedKeys;
        try {
            allowedKeys = landingContentDAO.allKeys();
        } catch (Exception e) {
            serverError(resp, "Could not load landing page content. Run DB migrations and try again.");
            return;
        }

        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
            String key = e.getKey();
            if ("action".equals(key)) continue;
            if (!allowedKeys.contains(key)) {
                badRequest(resp, "Unknown content key: " + key);
                return;
            }
            String[] values = e.getValue();
            String value = (values == null || values.length == 0 || values[0] == null) ? "" : values[0].trim();
            String violation = getValueViolation(value);
            if (violation != null) {
                badRequest(resp, violation);
                return;
            }
            updates.put(key, value);
        }
        if (updates.isEmpty()) {
            badRequest(resp, "No content fields supplied.");
            return;
        }

        try {
            int saved = landingContentDAO.updateAll(updates, sessionUserId(req));
            LandingContentApiServlet.invalidateCache();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("updated", saved);
            body.put("message", saved == 1 ? "1 field saved." : saved + " fields saved.");
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not save landing page content.");
        }
    }

    private void handleReset(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String key   = param(req, "key");
        String group = param(req, "group");
        if (key == null && group == null) {
            badRequest(resp, "key or group is required to reset.");
            return;
        }
        try {
            if (key != null) {
                if (!landingContentDAO.resetToDefault(key, sessionUserId(req))) {
                    error(resp, 404, "Unknown content key: " + key);
                    return;
                }
                LandingContentApiServlet.invalidateCache();
                okMsg(resp, "Field restored to its default.");
                return;
            }
            int reset = landingContentDAO.resetGroup(group, sessionUserId(req));
            if (reset == 0) {
                error(resp, 404, "Unknown content group: " + group);
                return;
            }
            LandingContentApiServlet.invalidateCache();
            okMsg(resp, reset + " field(s) restored to their defaults.");
        } catch (Exception e) {
            serverError(resp, "Could not restore the default content.");
        }
    }

    /**
     * Returns a rejection message for a submitted value, or {@code null} when it is valid.
     *
     * <p>Values are stored raw rather than run through {@code SecurityUtil.sanitize}: that
     * helper escapes quotes and would progressively mangle the typographic punctuation in
     * this copy on every save. Angle brackets are rejected instead, and React escapes the
     * text on render, so no markup can reach the page.</p>
     */
    static String getValueViolation(String value) {
        if (value == null || value.isBlank()) {
            return "Content cannot be empty. Use \"reset to default\" to restore the original wording.";
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            return "Content must be at most " + MAX_VALUE_LENGTH + " characters.";
        }
        if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
            return "Content cannot contain < or >.";
        }
        return null;
    }
}
