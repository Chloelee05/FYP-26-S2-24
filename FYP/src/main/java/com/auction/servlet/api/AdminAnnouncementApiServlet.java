package com.auction.servlet.api;

import com.auction.dao.AnnouncementDAO;
import com.auction.model.admin.Announcement;
import com.auction.notification.NotificationService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin broadcast API for system-wide announcements.
 *
 * <pre>
 * GET  /api/admin/announcements
 *      The most recent broadcasts (newest first) with their reach — the admin history.
 *
 * POST /api/admin/announcements
 *      title      required, up to 150 characters, single line
 *      message    required, up to 2000 characters
 *      audience   optional, ALL (default) | BUYERS | SELLERS
 *      severity   optional, INFO (default) | WARNING | CRITICAL
 *      link       optional in-app path, e.g. /profile
 *      sendEmail  optional, "true" to also email every recipient
 * </pre>
 *
 * <p>Both require the ADMIN role. The exact mapping below takes precedence over
 * {@code AdminApiServlet}'s {@code /api/admin/*} prefix mapping, so announcements stay in
 * their own servlet rather than growing that one further.</p>
 *
 * <p>A broadcast writes one notification per active user in the audience, which is how it
 * reaches them: the notification bell users already poll picks the announcement up with no
 * frontend change. Email is opt-in and best-effort — it never fails a broadcast that has
 * already been delivered in-app.</p>
 */
@WebServlet("/api/admin/announcements")
public class AdminAnnouncementApiServlet extends ApiBase {

    /** How many past announcements the history returns. */
    private static final int HISTORY_LIMIT = 50;

    private AnnouncementDAO dao = new AnnouncementDAO();

    /** Test hook */
    public void setAnnouncementDAO(AnnouncementDAO dao) { this.dao = dao; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        try {
            ok(resp, dao.listRecent(HISTORY_LIMIT));
        } catch (Exception e) {
            serverError(resp, "Could not load announcements.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;

        String title   = Announcement.normalize(param(req, "title"));
        String message = Announcement.normalize(param(req, "message"));
        String link    = Announcement.normalize(param(req, "link"));

        String violation = Announcement.violationForTitle(title);
        if (violation == null) violation = Announcement.violationForMessage(message);
        if (violation == null) violation = Announcement.violationForLink(link);
        if (violation != null) { badRequest(resp, violation); return; }

        String audienceRaw = param(req, "audience");
        Announcement.Audience audience = audienceRaw == null
                ? Announcement.Audience.ALL
                : Announcement.Audience.parse(audienceRaw);
        if (audience == null) {
            badRequest(resp, "Audience must be one of " + Announcement.Audience.allowedValues() + ".");
            return;
        }

        String severityRaw = param(req, "severity");
        Announcement.Severity severity = severityRaw == null
                ? Announcement.Severity.INFO
                : Announcement.Severity.parse(severityRaw);
        if (severity == null) {
            badRequest(resp, "Severity must be one of " + Announcement.Severity.allowedValues() + ".");
            return;
        }

        Integer adminId = sessionUserId(req);

        Announcement sent;
        try {
            sent = dao.broadcast(
                    Announcement.compose(title, message, audience, severity, link, adminId));
        } catch (Exception e) {
            serverError(resp, "Could not send the announcement.");
            return;
        }

        int emailed = emailCopies(req, sent, audience);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", summary(sent, audience, emailed));
        body.put("announcement", sent);
        body.put("recipientCount", sent.getRecipientCount());
        body.put("emailedCount", emailed);
        ok(resp, body);
    }

    /** Confirmation line an admin reads after sending, e.g. "Announcement sent to 42 users." */
    private static String summary(Announcement sent, Announcement.Audience audience, int emailed) {
        int reach = sent.getRecipientCount();
        StringBuilder sb = new StringBuilder("Announcement sent to ")
                .append(reach)
                .append(reach == 1 ? " user" : " users");
        if (audience != Announcement.Audience.ALL) sb.append(" (").append(audience.describe()).append(')');
        sb.append('.');
        if (emailed > 0) sb.append(" Emailed ").append(emailed).append(emailed == 1 ? " copy." : " copies.");
        return sb.toString();
    }

    /**
     * Sends the optional email copy. The announcement is already delivered in-app by the time
     * this runs, so a mail failure is swallowed rather than turned into an error the admin
     * would read as "the announcement did not go out".
     *
     * @return how many emails were accepted, 0 when the admin did not ask for any
     */
    private int emailCopies(HttpServletRequest req, Announcement sent, Announcement.Audience audience) {
        if (!"true".equalsIgnoreCase(req.getParameter("sendEmail"))) return 0;
        try {
            return NotificationService.emailAnnouncement(sent, dao.recipientEmails(audience));
        } catch (Exception e) {
            return 0;
        }
    }
}
