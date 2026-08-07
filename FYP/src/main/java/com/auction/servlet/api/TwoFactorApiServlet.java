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
 *
 * <p>Two different second factors live here. Login verification uses the emailed one-time code
 * that {@code AuthApiServlet} generated, while setup, confirm and disable manage a TOTP
 * authenticator app secret through {@link TotpUtil}. The stored secret is encrypted with
 * {@link SecurityUtil} before it goes into {@code users}.</p>
 *
 * <p>/verify-login is reachable without a full session, because the caller only holds the
 * pending token issued at login; the other three sit behind AuthFilter. Disabling requires the
 * account password, so somebody at an unattended browser cannot quietly remove the protection.</p>
 */
@WebServlet("/api/2fa/*")
public class TwoFactorApiServlet extends ApiBase {

    private UserDAO userDAO;

    public TwoFactorApiServlet() {
        this.userDAO = new UserDAO();
    }

    /** Test hook: lets a unit test supply a stub DAO. */
    public void setUserDAO(UserDAO userDAO) { this.userDAO = userDAO; }

    /** Routes the four 2FA actions by path segment. All are POST because each one changes state. */
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

    /**
     * POST /api/2fa/verify-login with {@code otpCode}. Second half of the login for an account
     * with 2FA on. The caller sends the pending token from {@code AuthApiServlet}; if the code
     * matches, the same session object is upgraded in place into a real login and the identity
     * fields are returned exactly as an ordinary login would return them.
     */
    private void handleVerifyLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Only a session explicitly marked as awaiting 2FA may be upgraded here, so an ordinary
        // token cannot be pushed through this route.
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

        // Clear the pending markers and the stored code before granting the session, so the same
        // code cannot be replayed, then fill in the attributes a logged-in session needs.
        session.removeAttribute("awaitingTwoFactor");
        session.removeAttribute("pendingUserId");
        session.removeAttribute("pendingUserEmail");
        session.removeAttribute("pending2faOtp");
        session.setMaxInactiveInterval(60 * 30);
        session.setAttribute("userId",           user.getId());
        session.setAttribute("userRole",         user.getRole().name());
        session.setAttribute("canSell",         user.canSell());
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
        body.put("canSell",             user.canSell());
        body.put("profileImageUrl",  user.getProfileImageUrl());
        body.put("twoFactorEnabled", true);
        ok(resp, body);
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/2fa/setup. Generates a TOTP secret and returns it with an otpauth URI the SPA
     * renders as a QR code. The secret is only held on the session at this point; nothing is
     * written to the database until /confirm proves the authenticator app actually works.
     */
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

    /**
     * POST /api/2fa/confirm with {@code otpCode}. Checks the first code from the authenticator
     * against the pending secret and only then encrypts and stores it. Requiring a working code
     * first is what stops a member locking themselves out with a QR they never scanned.
     */
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

    /**
     * POST /api/2fa/disable. Despite the documented {@code otpCode} parameter this route
     * actually re-checks the account {@code password}, then clears the stored secret. Turning
     * off a security control needs proof of identity beyond holding the session.
     */
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
