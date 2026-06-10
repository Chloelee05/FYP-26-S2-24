package com.auction.servlet.api;

import com.auction.dao.SupportChatDAO;
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
 */
@WebServlet("/api/support/*")
public class SupportApiServlet extends ApiBase {

    private static final Set<String> ALLOWED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("image/jpeg", "image/png", "image/gif", "image/webp")));
    private static final String UPLOAD_SUBDIR = "support";
    private static final String UPLOAD_DIR = UploadedFileServlet.BASE_DIR + File.separator + UPLOAD_SUBDIR;
    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024L;

    private final SupportChatDAO chatDAO = new SupportChatDAO();

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
        } else {
            error(resp, 404, "Not found.");
        }
    }

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

    private void handleListThreads(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        int userId = ((Number) session.getAttribute("userId")).intValue();
        boolean admin = isAdmin(session);
        try {
            List<Map<String, Object>> threads = admin
                    ? chatDAO.listThreadsForAdmin()
                    : chatDAO.listThreadsForUser(userId);
            ok(resp, threads);
        } catch (Exception e) {
            serverError(resp, "Could not load support threads.");
        }
    }

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
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("threadId", threadId);
            out.put("message", "Support thread created.");
            ok(resp, out);
        } catch (Exception e) {
            serverError(resp, "Could not create support thread.");
        }
    }

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
            okMsg(resp, "Message sent.");
        } catch (Exception e) {
            serverError(resp, "Could not send message.");
        }
    }

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

    private String idStr(HttpServletRequest req) {
        String[] parts = parts(req);
        return parts.length >= 2 ? parts[1] : "";
    }

    private long parseId(String s, HttpServletResponse resp) throws IOException {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid thread ID."); return -1; }
    }

    private String[] parts(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return new String[0];
        return p.replaceFirst("^/", "").split("/");
    }
}
