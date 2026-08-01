package com.auction.servlet.api;

import com.auction.dao.TelegramLinkDAO;
import com.auction.telegram.TelegramConfig;
import com.auction.telegram.TelegramCopy;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Account-side Telegram linking (the bot side lives in {@link TelegramWebhookServlet}).
 *
 * <pre>
 * GET  /api/telegram/status      — connection state + the admin-editable dialog copy
 * POST /api/telegram/link/start  — mint a deep-link token and a manual OTP
 * POST /api/telegram/unlink      — disconnect this account
 * </pre>
 *
 * <p>Every endpoint requires a session. The two linking paths deliberately mint their
 * secrets together: the deep link is the happy path, the OTP the fallback for a desktop
 * browser with Telegram on a different device, and both redeem through the same
 * single-use consumption in {@link TelegramLinkDAO}.</p>
 */
@WebServlet("/api/telegram/*")
public class TelegramApiServlet extends ApiBase {

    /** Deep-link tokens are 32 random bytes; Telegram allows up to 64 base64url characters. */
    private static final int DEEP_LINK_TOKEN_BYTES = 32;

    /** A user may start linking this many times per {@link #START_WINDOW_MINUTES}. */
    static final int MAX_STARTS_PER_WINDOW = 5;
    static final int START_WINDOW_MINUTES = 15;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TelegramLinkDAO dao = new TelegramLinkDAO();

    /** Test hook */
    public void setTelegramLinkDAO(TelegramLinkDAO dao) {
        this.dao = dao;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        if ("/status".equals(path)) {
            handleStatus(req, resp);
        } else {
            error(resp, 404, "Unknown Telegram endpoint");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        switch (path) {
            case "/link/start": handleLinkStart(req, resp); break;
            case "/unlink":     handleUnlink(req, resp);    break;
            default: error(resp, 404, "Unknown Telegram endpoint"); break;
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", TelegramConfig.isConfigured());
        body.put("botUsername", TelegramConfig.botUsername());
        body.put("copy", TelegramCopy.dialogCopy());

        if (!TelegramConfig.isConfigured()) {
            // Fail soft: without a bot the feature is simply unavailable, not broken.
            body.put("linked", false);
            ok(resp, body);
            return;
        }

        try {
            TelegramLinkDAO.LinkInfo link = dao.findByUserId(sessionUserId(req));
            body.put("linked", link != null);
            if (link != null) {
                body.put("telegramUsername", link.telegramUsername);
                body.put("linkedAt", link.linkedAt);
            }
            ok(resp, body);
        } catch (RuntimeException e) {
            serverError(resp, "Could not load your Telegram connection. Run DB migrations and try again.");
        }
    }

    private void handleLinkStart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        if (!TelegramConfig.isConfigured()) {
            error(resp, 503, "Telegram notifications are not available on this server.");
            return;
        }
        int userId = sessionUserId(req);

        try {
            // Counted in the database rather than in memory so a restart cannot reset it,
            // and so the cap holds across instances.
            if (dao.countCodesMintedSince(userId, START_WINDOW_MINUTES) >= MAX_STARTS_PER_WINDOW) {
                error(resp, 429, "Too many connection attempts. Please wait a few minutes and try again.");
                return;
            }

            String token = randomToken();
            String otp = randomOtp();
            dao.mintCodes(userId, token, otp);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deepLink", TelegramConfig.deepLink(token));
            body.put("code", otp);
            body.put("botUsername", TelegramConfig.botUsername());
            body.put("expiresAt", Instant.now().plus(TelegramLinkDAO.CODE_TTL_MINUTES, ChronoUnit.MINUTES));
            body.put("expiresInSeconds", TelegramLinkDAO.CODE_TTL_MINUTES * 60);
            ok(resp, body);
        } catch (RuntimeException e) {
            serverError(resp, "Could not start the Telegram connection. Please try again.");
        }
    }

    private void handleUnlink(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        try {
            boolean removed = dao.unlinkUser(sessionUserId(req));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("linked", false);
            body.put("message", removed
                    ? "Telegram disconnected. You will not receive any more alerts there."
                    : "No Telegram account was connected.");
            ok(resp, body);
        } catch (RuntimeException e) {
            serverError(resp, "Could not disconnect Telegram. Please try again.");
        }
    }

    // -------------------------------------------------------------------------
    // Secret generation
    // -------------------------------------------------------------------------

    /** URL-safe, unpadded so it survives being pasted into a {@code t.me} link. */
    private static String randomToken() {
        byte[] bytes = new byte[DEEP_LINK_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomOtp() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
