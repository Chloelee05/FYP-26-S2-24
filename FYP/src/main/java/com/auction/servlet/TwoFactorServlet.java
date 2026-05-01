package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;
import com.auction.util.TotpUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Manages the 2FA lifecycle for an authenticated user's settings page.
 *
 * Three actions (passed as the {@code action} POST parameter):
 * <ol>
 *   <li><b>setup</b>   — generates a TOTP secret, stores it temporarily in the session,
 *                        and returns the {@code otpauth://} URI for QR-code display.</li>
 *   <li><b>confirm</b> — verifies the user's first TOTP code against the pending secret;
 *                        on success, encrypts the secret and persists it to the DB.</li>
 *   <li><b>disable</b> — verifies a current TOTP code against the stored secret;
 *                        on success, clears the 2FA data from the DB.</li>
 * </ol>
 *
 * All actions require an authenticated session (RBAC enforced via {@link RbacUtil}).
 */
public class TwoFactorServlet extends HttpServlet {

    private UserDAO userDAO;

    public TwoFactorServlet() {
        userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);

        if (!RbacUtil.isAuthenticated(session)) {
            req.setAttribute("Error", "Authentication required.");
            return;
        }

        String action = req.getParameter("action");
        if (action == null || action.isBlank()) {
            req.setAttribute("Error", "Action is required.");
            return;
        }

        String email = (String) session.getAttribute("sessionEmail");

        switch (action.trim().toLowerCase()) {
            case "setup":
                handleSetup(req, session, email);
                break;
            case "confirm":
                handleConfirm(req, session, email);
                break;
            case "disable":
                handleDisable(req, session, email);
                break;
            default:
                req.setAttribute("Error", "Unknown action: " + action);
        }
    }

    // action handlers

    private void handleSetup(HttpServletRequest req, HttpSession session, String email) {
        String secret = TotpUtil.generateSecret();
        session.setAttribute("pending2faSecret", secret);

        String uri = TotpUtil.generateTotpUri(secret, email, "OnlineAuction");
        req.setAttribute("totpUri", uri);
        req.setAttribute("totpSecret", secret);
        req.setAttribute("Setup", "Scan the QR code or enter the secret key, then confirm with your authenticator code.");
    }

    private void handleConfirm(HttpServletRequest req, HttpSession session, String email) {
        String pendingSecret = (String) session.getAttribute("pending2faSecret");
        String otpCode       = req.getParameter("otpCode");

        if (pendingSecret == null) {
            req.setAttribute("Error", "No pending 2FA setup found. Please start setup again.");
            return;
        }
        if (otpCode == null || otpCode.isBlank()) {
            req.setAttribute("Error", "Authenticator code is required.");
            return;
        }
        if (!TotpUtil.verifyCode(pendingSecret, otpCode)) {
            req.setAttribute("Error", "Invalid authenticator code. Please try again.");
            return;
        }

        String encryptedSecret = SecurityUtil.encrypt(pendingSecret);
        if (!userDAO.enableTwoFactor(email, encryptedSecret)) {
            req.setAttribute("Error", "Failed to enable 2FA. Please try again.");
            return;
        }

        session.removeAttribute("pending2faSecret");
        session.setAttribute("twoFactorEnabled", true);
        req.setAttribute("TwoFactorEnabled", "Two-factor authentication has been enabled.");
    }

    private void handleDisable(HttpServletRequest req, HttpSession session, String email) {
        String otpCode = req.getParameter("otpCode");

        if (otpCode == null || otpCode.isBlank()) {
            req.setAttribute("Error", "Authenticator code is required to disable 2FA.");
            return;
        }

        User user = userDAO.getUserByEmail(email);
        if (user == null || !user.isTwoFactorEnabled()) {
            req.setAttribute("Error", "2FA is not enabled on this account.");
            return;
        }

        String plainSecret = SecurityUtil.decrypt(user.getTwoFactorSecret());
        if (!TotpUtil.verifyCode(plainSecret, otpCode)) {
            req.setAttribute("Error", "Invalid authenticator code.");
            return;
        }

        if (!userDAO.disableTwoFactor(email)) {
            req.setAttribute("Error", "Failed to disable 2FA. Please try again.");
            return;
        }

        session.setAttribute("twoFactorEnabled", false);
        req.setAttribute("TwoFactorDisabled", "Two-factor authentication has been disabled.");
    }
}
