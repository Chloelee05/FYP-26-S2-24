package com.auction.servlet.api;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import com.auction.util.TotpUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON API for 2FA management under /api/2fa/*.
 *
 * POST /api/2fa/verify-login  params: otpCode  — completes login for users with 2FA enabled
 * POST /api/2fa/setup                          — generates TOTP secret, returns URI + secret
 * POST /api/2fa/confirm       params: otpCode  — verifies first code and persists secret to DB
 * POST /api/2fa/disable       params: otpCode  — verifies code and clears 2FA from DB
 */
@WebServlet("/api/2fa/*")
public class TwoFactorApiServlet extends ApiBase {

    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        switch (path) {
            case "/verify-login": handleVerifyLogin(req, resp); break;
            case "/setup":        handleSetup(req, resp);       break;
            case "/confirm":      handleConfirm(req, resp);     break;
            case "/disable":      handleDisable(req, resp);     break;
            default: error(resp, 404, "Unknown 2FA endpoint");  break;
        }
    }

    // ── verify-login ──────────────────────────────────────────────────────────
    private void handleVerifyLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute("awaitingTwoFactor"))) {
            error(resp, 401, "No pending 2FA verification.");
            return;
        }

        String otpCode = param(req, "otpCode");
        if (otpCode == null) { badRequest(resp, "Verification code is required."); return; }

        Object pendingIdObj = session.getAttribute("pendingUserId");
        if (pendingIdObj == null) { error(resp, 401, "Session expired. Please log in again."); return; }
        int userId = ((Number) pendingIdObj).intValue();

        String pendingOtp = (String) session.getAttribute("pending2faOtp");
        if (pendingOtp == null) { error(resp, 401, "Session expired. Please log in again."); return; }

        if (!pendingOtp.equals(otpCode.trim())) {
            error(resp, 401, "Invalid verification code.");
            return;
        }

        User user = userDAO.getUserById(userId);
        if (user == null) { serverError(resp, "User not found."); return; }

        session.removeAttribute("awaitingTwoFactor");
        session.removeAttribute("pendingUserId");
        session.removeAttribute("pendingUserEmail");
        session.removeAttribute("pending2faOtp");
        session.setMaxInactiveInterval(60 * 30);
        session.setAttribute("userId",           user.getId());
        session.setAttribute("userRole",         user.getRole().name());
        session.setAttribute("sessionEmail",     user.getEmail());
        session.setAttribute("twoFactorEnabled", true);
        session.setAttribute("maskedEmail",      SecurityUtil.maskEmail(user.getEmail()));
        session.setAttribute("maskedUsername",   SecurityUtil.maskUsername(user.getUsername()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token",           session.getToken());
        body.put("id",               user.getId());
        body.put("username",         user.getUsername());
        body.put("email",            user.getEmail());
        body.put("role",             user.getRole().name());
        body.put("profileImageUrl",  user.getProfileImageUrl());
        body.put("twoFactorEnabled", true);
        ok(resp, body);
    }

    // ── setup ─────────────────────────────────────────────────────────────────
    private void handleSetup(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        String email = (String) session.getAttribute("sessionEmail");

        String secret = TotpUtil.generateSecret();
        session.setAttribute("pending2faSecret", secret);

        String uri = TotpUtil.generateTotpUri(secret, email, "AuctionHub");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totpUri",    uri);
        body.put("totpSecret", secret);
        ok(resp, body);
    }

    // ── confirm ───────────────────────────────────────────────────────────────
    private void handleConfirm(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);

        String pendingSecret = (String) session.getAttribute("pending2faSecret");
        String otpCode       = param(req, "otpCode");

        if (pendingSecret == null) { badRequest(resp, "No pending 2FA setup. Please start setup again."); return; }
        if (otpCode == null)       { badRequest(resp, "Authenticator code is required."); return; }
        if (!TotpUtil.verifyCode(pendingSecret, otpCode)) {
            error(resp, 400, "Invalid authenticator code. Please try again.");
            return;
        }

        String email           = (String) session.getAttribute("sessionEmail");
        String encryptedSecret = SecurityUtil.encrypt(pendingSecret);
        if (!userDAO.enableTwoFactor(email, encryptedSecret)) {
            serverError(resp, "Failed to enable 2FA. Please try again.");
            return;
        }

        session.removeAttribute("pending2faSecret");
        session.setAttribute("twoFactorEnabled", true);
        okMsg(resp, "Two-factor authentication has been enabled.");
    }

    // ── disable ───────────────────────────────────────────────────────────────
    private void handleDisable(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        String email = (String) session.getAttribute("sessionEmail");

        String password = req.getParameter("password");
        if (password == null || password.isBlank()) { badRequest(resp, "Password is required to disable 2FA."); return; }

        User user = userDAO.getUserByEmail(email);
        if (user == null || !user.isTwoFactorEnabled()) {
            badRequest(resp, "2FA is not enabled on this account.");
            return;
        }

        if (!SecurityUtil.verifyPassword(password, user.getPassword())) {
            error(resp, 400, "Incorrect password.");
            return;
        }

        if (!userDAO.disableTwoFactor(email)) {
            serverError(resp, "Failed to disable 2FA. Please try again.");
            return;
        }

        session.setAttribute("twoFactorEnabled", false);
        okMsg(resp, "Two-factor authentication has been disabled.");
    }
}
