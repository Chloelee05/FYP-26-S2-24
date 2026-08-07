package com.auction.servlet.api;

import com.auction.dao.SupportChatDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.notification.NotificationService;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GET  /api/support/threads
 * POST /api/support/threads              (subject, body)
 * POST /api/support/upload               (raw image body)
 * GET  /api/support/threads/{id}/messages
 * POST /api/support/threads/{id}/messages (body, attachmentUrl?)
 * POST /api/support/threads/{id}/close
 * POST /api/support/threads/{id}/read
 *
 * <p>Support ticketing between a member and the admin team, distinct from the buyer to seller
 * conversation in {@code OrderMessageApiServlet}. Everything is behind AuthFilter.</p>
 *
 * <p>Access is decided by {@link #canAccessThread}: an admin may open any thread, a member only
 * their own. Threads are always opened by members, so an admin is refused there; closing is the
 * reverse and is admin only. Message text is sanitised because both sides read it, and image
 * attachments are written under the /uploads directory with a generated filename.</p>
 */
@WebServlet("/api/support/*")
public class SupportApiServlet extends ApiBase {

    private static final Set<String> ALLOWED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("image/jpeg", "image/png", "image/gif", "image/webp")));
    private static final String UPLOAD_SUBDIR = "support";
    private static final String UPLOAD_DIR = UploadedFileServlet.BASE_DIR + File.separator + UPLOAD_SUBDIR;
    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024L;

    private final SupportChatDAO chatDAO = new SupportChatDAO();
    private final UserDAO userDAO = new UserDAO();

    /**
     * Routes the reads by path shape: a bare /threads lists them, /threads/{id}/messages returns
     * one conversation. Requires a session; the per-thread access check happens in the handler.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String[] parts = parts(req);
        if (parts.length == 0 || "threads".equals(parts[0]) && parts.length == 1) {
            handleListThreads(req, resp);
        } else if (parts.length >= 3 && "threads".equals(parts[0]) && "messages".equals(parts[2])) {
            handleGetMessages(req, resp);
        } else {
            error(resp, 404, "Not found.");
        }
    }

    /**
     * Routes the writes: create a thread, upload an attachment, send a message, close a thread or
     * mark one read. The trailing path segment names the action on an existing thread.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String[] parts = parts(req);
        if (parts.length >= 1 && "threads".equals(parts[0]) && parts.length == 1) {
            handleCreateThread(req, resp);
        } else if (parts.length == 1 && "upload".equals(parts[0])) {
            handleUpload(req, resp);
        } else if (parts.length >= 3 && "threads".equals(parts[0]) && "messages".equals(parts[2])) {
            handleSendMessage(req, resp);
        } else if (parts.length >= 3 && "threads".equals(parts[0]) && "close".equals(parts[2])) {
            handleCloseThread(req, resp);
        } else if (parts.length >= 3 && "threads".equals(parts[0]) && "read".equals(parts[2])) {
            handleMarkRead(req, resp);
        } else {
            error(resp, 404, "Not found.");
        }
    }

    /**
     * POST /api/support/threads/{id}/read. Clears the unread badge for whichever side is calling,
     * so the same endpoint serves both the member and the admin.
     */
    private void handleMarkRead(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long threadId = parseId(idStr(req), resp);
        if (threadId < 0) return;
        if (!canAccessThread(req, threadId, resp)) return;
        AuthSession session = authSession(req);
        int userId = ((Number) session.getAttribute("userId")).intValue();
        try {
            chatDAO.markThreadRead(threadId, userId);
            okMsg(resp, "Marked as read.");
        } catch (Exception e) {
            serverError(resp, "Could not update read status.");
        }
    }

    /**
     * POST /api/support/upload. The body is the raw image bytes; the reply is the URL to pass
     * back as {@code attachmentUrl} when sending the message. Same allow-list and 5 MB cap as the
     * other uploads, and the filename is generated rather than taken from the client.
     */
    private void handleUpload(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String contentType = req.getContentType();
        if (contentType == null) contentType = "";
        String mime = contentType.split(";")[0].trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(mime)) {
            badRequest(resp, "Only JPEG, PNG, GIF, and WebP images are allowed.");
            return;
        }
        long len = req.getContentLengthLong();
        if (len > MAX_UPLOAD_BYTES) {
            badRequest(resp, "File too large (max 5 MB).");
            return;
        }
        String ext = mime.contains("png") ? ".png"
                : mime.contains("webp") ? ".webp"
                : mime.contains("gif") ? ".gif" : ".jpg";
        String filename = UUID.randomUUID() + ext;
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) uploadDir.mkdirs();
        try (InputStream in = req.getInputStream()) {
            Files.copy(in, Paths.get(uploadDir.getAbsolutePath(), filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            serverError(resp, "Failed to save uploaded file.");
            return;
        }
        ok(resp, Collections.singletonMap("imageUrl", "/uploads/" + UPLOAD_SUBDIR + "/" + filename));
    }

    /**
     * GET /api/support/threads. An admin gets the whole queue, a member gets only their own
     * threads. The role decides which DAO query runs, so the filtering happens in SQL rather
     * than by trimming a full list afterwards.
     */
    private void handleListThreads(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        int userId = ((Number) session.getAttribute("userId")).intValue();
        boolean admin = isAdmin(session);
        try {
            List<Map<String, Object>> threads = admin
                    ? chatDAO.listThreadsForAdmin(userId)
                    : chatDAO.listThreadsForUser(userId);
            ok(resp, threads);
        } catch (Exception e) {
            serverError(resp, "Could not load support threads.");
        }
    }

    /**
     * POST /api/support/threads with {@code subject}, {@code body} and optional
     * {@code attachmentUrl}. Opens a ticket and posts the first message. An admin is refused,
     * because support threads run from a member to the platform and never the other way.
     */
    private void handleCreateThread(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (isAdmin(session)) { forbidden(resp); return; }
        int userId = ((Number) session.getAttribute("userId")).intValue();
        String subject = SecurityUtil.sanitize(param(req, "subject"));
        String body = SecurityUtil.sanitize(param(req, "body"));
        String attachmentUrl = param(req, "attachmentUrl");
        if ((body == null || body.isBlank()) && (attachmentUrl == null || attachmentUrl.isBlank())) {
            badRequest(resp, "body or attachmentUrl is required."); return;
        }
        try {
            long threadId = chatDAO.createThread(userId, subject);
            if (threadId < 0) { serverError(resp, "Could not create thread."); return; }
            chatDAO.addMessage(threadId, userId, body, attachmentUrl);
            User user = userDAO.getUserById(userId);
            String preview = (body != null && !body.isBlank()) ? body
                    : (attachmentUrl != null && !attachmentUrl.isBlank() ? "[Image attached]" : "New message");
            NotificationService.notifyAdminsSupportMessage(threadId,
                    user != null ? user.getUsername() : null, preview);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("threadId", threadId);
            out.put("message", "Support thread created.");
            ok(resp, out);
        } catch (Exception e) {
            serverError(resp, "Could not create support thread.");
        }
    }

    /** GET /api/support/threads/{id}/messages. Returns the conversation once the access check passes. */
    private void handleGetMessages(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long threadId = parseId(idStr(req), resp);
        if (threadId < 0) return;
        if (!canAccessThread(req, threadId, resp)) return;
        try {
            ok(resp, chatDAO.listMessages(threadId));
        } catch (Exception e) {
            serverError(resp, "Could not load messages.");
        }
    }

    /**
     * POST /api/support/threads/{id}/messages with {@code body} and optional
     * {@code attachmentUrl}. Either one alone is enough, so an image can be sent with no text.
     * A closed thread is refused. Admins are only alerted when the sender is a member, so a reply
     * from one admin does not notify the whole team about their own message.
     */
    private void handleSendMessage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long threadId = parseId(idStr(req), resp);
        if (threadId < 0) return;
        if (!canAccessThread(req, threadId, resp)) return;
        String body = SecurityUtil.sanitize(param(req, "body"));
        String attachmentUrl = param(req, "attachmentUrl");
        if ((body == null || body.isBlank()) && (attachmentUrl == null || attachmentUrl.isBlank())) {
            badRequest(resp, "body or attachmentUrl is required."); return;
        }
        AuthSession session = authSession(req);
        int userId = ((Number) session.getAttribute("userId")).intValue();
        try {
            Map<String, Object> thread = chatDAO.getThread(threadId);
            if (thread != null && "CLOSED".equals(thread.get("status"))) {
                error(resp, 400, "This thread is closed."); return;
            }
            long msgId = chatDAO.addMessage(threadId, userId, body, attachmentUrl);
            if (msgId < 0) { serverError(resp, "Could not send message."); return; }
            if (!isAdmin(session)) {
                User user = userDAO.getUserById(userId);
                String preview = (body != null && !body.isBlank()) ? body
                        : (attachmentUrl != null && !attachmentUrl.isBlank() ? "[Image attached]" : "New message");
                NotificationService.notifyAdminsSupportMessage(threadId,
                        user != null ? user.getUsername() : null, preview);
            }
            okMsg(resp, "Message sent.");
        } catch (Exception e) {
            serverError(resp, "Could not send message.");
        }
    }

    /**
     * POST /api/support/threads/{id}/close. Admin only, so a member cannot close a ticket that
     * the team still has open. A closed thread rejects further messages.
     */
    private void handleCloseThread(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        long threadId = parseId(idStr(req), resp);
        if (threadId < 0) return;
        try {
            if (chatDAO.closeThread(threadId)) okMsg(resp, "Thread closed.");
            else error(resp, 404, "Thread not found.");
        } catch (Exception e) {
            serverError(resp, "Could not close thread.");
        }
    }

    /**
     * The authorisation rule for a single thread: admins pass, everyone else must own it.
     * Writes the 403 itself and returns false, so callers guard with
     * {@code if (!canAccessThread(...)) return;}. A DAO failure falls through to denied.
     */
    private boolean canAccessThread(HttpServletRequest req, long threadId, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (isAdmin(session)) return true;
        int userId = ((Number) session.getAttribute("userId")).intValue();
        try {
            if (chatDAO.threadBelongsToUser(threadId, userId)) return true;
        } catch (Exception ignored) { }
        forbidden(resp);
        return false;
    }

    /** The {id} segment of /threads/{id}/..., or "" when the path has no id. */
    private String idStr(HttpServletRequest req) {
        String[] parts = parts(req);
        return parts.length >= 2 ? parts[1] : "";
    }

    /** Parses the thread id, writing the 400 itself and returning -1 so callers guard on a negative result. */
    private long parseId(String s, HttpServletResponse resp) throws IOException {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid thread ID."); return -1; }
    }

    /** Splits the path after /api/support into segments, which is how both doGet and doPost route. */
    private String[] parts(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return new String[0];
        return p.replaceFirst("^/", "").split("/");
    }
}
