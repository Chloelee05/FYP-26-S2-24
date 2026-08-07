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
 *
 * <p>Legacy code from the JSP era. Note that the class carries no {@code @WebServlet}
 * annotation and is not declared in {@code web.xml}, so it is not currently reachable over
 * HTTP; the live 2FA feature is {@code TwoFactorApiServlet} on {@code /api/2fa/*}, which the
 * SPA calls. The logic below is still the reference for how the TOTP secret is handled.</p>
 *
 * <p>The pending secret lives in the session between setup and confirm and is only written to
 * the database once the user has proved they can generate a valid code from it. It is stored
 * encrypted through {@link SecurityUtil#encrypt(String)}, so a leaked database dump does not
 * hand over working authenticator seeds.</p>
 */
public class TwoFactorServlet extends HttpServlet {

    private UserDAO userDAO;

    public TwoFactorServlet() {
        userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** Not implemented: the settings page that would host this form was never built in JSP. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    /**
     * Dispatches on the {@code action} parameter to setup, confirm or disable. The
     * authentication check comes first, before the action is even read, because all three
     * branches operate on the signed-in account taken from {@code session.sessionEmail} rather
     * than from anything the caller supplied.
     */
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

    /**
     * Generates a fresh TOTP secret and parks it in the session as {@code pending2faSecret}.
     * Nothing is written to the database yet, so abandoning the wizard leaves the account
     * exactly as it was.
     */
    private void handleSetup(HttpServletRequest req, HttpSession session, String email) {
        String secret = TotpUtil.generateSecret();
        session.setAttribute("pending2faSecret", secret);

        String uri = TotpUtil.generateTotpUri(secret, email, "OnlineAuction");
        req.setAttribute("totpUri", uri);
        req.setAttribute("totpSecret", secret);
        req.setAttribute("Setup", "Scan the QR code or enter the secret key, then confirm with your authenticator code.");
    }

    /**
     * Checks the first code the user reads off their authenticator against the pending secret,
     * and only then persists it encrypted. Requiring a working code before saving is what stops
     * a user locking themselves out with a QR code they never actually scanned.
     */
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

    /**
     * Turns 2FA off, but only after a currently valid code has been supplied. Decrypts the
     * stored secret to verify against it. Without that check, a hijacked session could quietly
     * strip the second factor off the account.
     */
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
