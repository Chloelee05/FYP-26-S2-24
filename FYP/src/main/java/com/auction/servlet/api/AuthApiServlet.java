package com.auction.servlet.api;

import com.auction.dao.PlatformSettingsDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.util.DevMode;
import com.auction.util.InputValidator;
import com.auction.util.LoginAttemptLimiter;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;
import com.auction.util.OtpStore;
import com.auction.notification.NotificationService;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import com.auction.util.TokenStore;
import jakarta.mail.MessagingException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles all auth API calls under /api/auth/*.
 *
 * POST /api/auth/login            params: email, password
 * POST /api/auth/logout
 * POST /api/auth/register         params: username, email, password, confirmPassword, termsAccept
 * POST /api/auth/forgot-password  params: identifier (email)
 * POST /api/auth/reset-password   params: identifier, otp, newPassword, confirmNewPassword
 * POST /api/auth/change-password  params: currentPassword, newPassword, confirmPassword
 *
 * <p>Entry point for the whole login lifecycle, called by the React SPA. Every path except
 * change-password is public and sits outside AuthFilter, which is why this class does its own
 * defending: {@link LoginAttemptLimiter} caps failed logins per email address, passwords are
 * only ever compared through {@link SecurityUtil} salted SHA-256 hashing, and the
 * forgot-password reply is identical whether or not the account exists so the endpoint cannot
 * be used to enumerate users.</p>
 *
 * <p>Collaborators: {@link UserDAO} for the {@code users} table, {@link OtpStore} for
 * short-lived reset and two-factor codes, {@link OtpMailer} for delivering them, and
 * {@link TokenStore} which issues the bearer token the SPA sends on every later request.
 * Lockout threshold and cooldown are read from {@code platform_settings} so an admin can
 * retune them without a redeploy.</p>
 */
@WebServlet("/api/auth/*")
public class AuthApiServlet extends ApiBase {

    private static final Logger LOG = Logger.getLogger(AuthApiServlet.class.getName());

    private UserDAO  userDAO;
    private OtpStore otpStore;
    private LoginAttemptLimiter loginAttemptLimiter;
    private PlatformSettingsDAO platformSettingsDAO;

    public AuthApiServlet() {
        this.userDAO  = new UserDAO();
        this.otpStore = new OtpStore();
        this.loginAttemptLimiter = LoginAttemptLimiter.getInstance();
        this.platformSettingsDAO = new PlatformSettingsDAO();
    }

    /** Test hook: the DAOs and stores are created in the constructor, so tests replace them here. */
    public void setUserDAO(UserDAO userDAO)  { this.userDAO  = userDAO; }
    public void setOtpStore(OtpStore otpStore) { this.otpStore = otpStore; }
    /** Test hook — swap in a fresh limiter so tests never share the process-wide singleton. */
    public void setLoginAttemptLimiter(LoginAttemptLimiter loginAttemptLimiter) { this.loginAttemptLimiter = loginAttemptLimiter; }
    public void setPlatformSettingsDAO(PlatformSettingsDAO platformSettingsDAO) { this.platformSettingsDAO = platformSettingsDAO; }

    /**
     * Routes POST /api/auth/* to the handler named by the path segment. All auth actions are
     * POST because they change state and must not end up in browser history or server logs
     * as a URL. An unrecognised path gives 404 rather than falling through to login.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) path = "/";

        switch (path) {
            case "/login":          handleLogin(req, resp);          break;
            case "/logout":         handleLogout(req, resp);         break;
            case "/register":       handleRegister(req, resp);       break;
            case "/forgot-password": handleForgot(req, resp);        break;
            case "/reset-password": handleReset(req, resp);          break;
            case "/change-password": handleChangePassword(req, resp); break;
            default: error(resp, 404, "Unknown auth endpoint"); break;
        }
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/auth/login with {@code email} and {@code password}. On success returns the
     * bearer token plus the user's identity, or, when two-factor is on, a short-lived
     * {@code pendingToken} and {@code requires2fa} flag that the SPA carries to
     * {@code TwoFactorApiServlet} instead of a real session.
     *
     * <p>Order matters here: lockout is checked first, then the password, then account status.
     * Failures answer 401 with one generic message so an attacker cannot tell a wrong password
     * from an unknown email; only the lockout returns a distinguishable 429.</p>
     */
    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String email    = param(req, "email");
        String password = req.getParameter("password");

        if (email == null) { badRequest(resp, "Email is required."); return; }
        if (password == null || password.isBlank()) { badRequest(resp, "Password is required."); return; }

        String emailErr = InputValidator.getEmailFormatViolation(email.toLowerCase());
        if (emailErr != null) { badRequest(resp, emailErr); return; }

        String accountKey = email.toLowerCase();
        int maxFailures = platformSettingsDAO.getInt("login_lockout_threshold", LoginAttemptLimiter.DEFAULT_MAX_FAILURES);
        Duration cooldown = Duration.ofMinutes(
                platformSettingsDAO.getInt("login_lockout_cooldown_minutes", LoginAttemptLimiter.DEFAULT_COOLDOWN_MINUTES));

        // Anti-brute-force: checked before touching the database, so a locked-out account
        // cannot be used to keep hammering the password hash comparison either.
        if (loginAttemptLimiter.isLockedOut(accountKey)) {
            long secondsLeft = loginAttemptLimiter.lockoutSecondsRemaining(accountKey);
            long minutesLeft = Math.max(1, (secondsLeft + 59) / 60);
            error(resp, 429, "Too many failed login attempts. Please try again in "
                    + minutesLeft + " minute" + (minutesLeft == 1 ? "" : "s") + ".");
            return;
        }

        User user = userDAO.getUserByEmail(accountKey);
        if (user == null || !SecurityUtil.verifyPassword(password, user.getPassword())) {
            loginAttemptLimiter.recordFailure(accountKey, maxFailures, cooldown);
            error(resp, 401, "Invalid email or password.");
            return;
        }
        // Correct password: reset the failure count even if a status check below still
        // blocks the login, since brute-force protection is only about guessing the password.
        loginAttemptLimiter.recordSuccess(accountKey);
        // Account status gates below. Each one gets its own wording because the user has already
        // proved they own the account, so telling them why they cannot get in is not a leak.
        if (user.getStatusId() == Status.SUSPENDED.getId()) {
            error(resp, 403, "Your account has been suspended.");
            return;
        }
        if (user.getStatusId() == Status.DELETED.getId()) {
            error(resp, 403, "This account is no longer available.");
            return;
        }
        if (user.getStatusId() == Status.PENDING.getId()) {
            error(resp, 403, "Your account is awaiting administrator approval.");
            return;
        }
        if (user.getStatusId() == Status.REJECTED.getId()) {
            error(resp, 403, "Your registration was not approved. Please contact support.");
            return;
        }

        // Two-factor path: the password was right but no usable session is issued yet.
        if (user.isTwoFactorEnabled()) {
            String otp = otpStore.generateAndStore(user.getEmail().toLowerCase());

            if (MailConfig.isSmtpConfigured()) {
                try {
                    OtpMailer.sendTwoFactorCode(user.getEmail(), otp);
                } catch (MessagingException e) {
                    LOG.warning("Failed to send 2FA code to " + user.getEmail() + ": " + e.getMessage());
                    serverError(resp, "Could not send verification email. Please try again.");
                    return;
                }
            } else {
                // Local development without SMTP: log the code so the login can still be completed.
                LOG.warning("SMTP not configured — 2FA OTP for " + user.getEmail() + ": " + otp);
            }

            // The pending session is not a login. It carries no userId or role, so AuthFilter and
            // every ApiBase guard reject it; only the two-factor verify step will trade it for a
            // real session. Five minutes is deliberately short.
            AuthSession pending = TokenStore.getInstance().create();
            pending.setMaxInactiveInterval(5 * 60);
            pending.setAttribute("awaitingTwoFactor", true);
            pending.setAttribute("pendingUserId",     user.getId());
            pending.setAttribute("pendingUserEmail",  user.getEmail());
            pending.setAttribute("pending2faOtp",     otp);

            Map<String, Object> twoFaBody = new LinkedHashMap<>();
            twoFaBody.put("requires2fa",  true);
            twoFaBody.put("pendingToken", pending.getToken());
            // Masked for PDPA: the screen shows something like j***n@e***.com, enough for the user
            // to recognise their own inbox without printing the full address.
            twoFaBody.put("maskedEmail",  SecurityUtil.maskEmail(user.getEmail()));
            // Only ever returned when dev mode is switched on, so a demo can proceed without SMTP.
            if (DevMode.isEnabled()) twoFaBody.put("devOtp", otp);
            ok(resp, twoFaBody);
            return;
        }

        // Normal login. Thirty minutes of inactivity ends the session. canSell is copied in here
        // because seller authorisation reads the capability flag, not the role, now that one
        // account can both buy and sell.
        AuthSession session = TokenStore.getInstance().create();
        session.setMaxInactiveInterval(60 * 30);
        session.setAttribute("userId",           user.getId());
        session.setAttribute("userRole",         user.getRole().name());
        session.setAttribute("canSell",         user.canSell());
        session.setAttribute("sessionEmail",     user.getEmail());
        session.setAttribute("twoFactorEnabled", false);
        session.setAttribute("maskedEmail",      SecurityUtil.maskEmail(user.getEmail()));
        session.setAttribute("maskedUsername",   SecurityUtil.maskUsername(user.getUsername()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token",    session.getToken());
        body.put("id",       user.getId());
        body.put("username", user.getUsername());
        body.put("email",    user.getEmail());
        body.put("role",     user.getRole().name());
        body.put("canSell",     user.canSell());
        body.put("profileImageUrl", user.getProfileImageUrl());
        body.put("twoFactorEnabled", false);
        ok(resp, body);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * POST /api/auth/logout. Deletes the token server side so it cannot be replayed even if a
     * copy survives in the browser. Only this tab's token is removed, so other tabs logged in
     * as other users stay signed in. Always answers 200, including for an already dead token.
     */
    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        TokenStore.getInstance().remove(bearerToken(req));
        okMsg(resp, "Logged out.");
    }

    // ── Register ──────────────────────────────────────────────────────────────

    /**
     * POST /api/auth/register with {@code username}, {@code email}, {@code password},
     * {@code confirmPassword} and {@code termsAccept}. Validates against
     * {@link InputValidator}, rejects a duplicate email or username with 409, stores the
     * password only as a salted hash, and creates the account in PENDING status.
     *
     * <p>No session is issued: the new account cannot sign in until an admin approves it, and
     * the admins are notified off the request thread.</p>
     */
    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username        = param(req, "username");
        String email           = param(req, "email");
        String password        = req.getParameter("password");
        String confirmPassword = req.getParameter("confirmPassword");
        // Accepts both spellings: "on" is what a plain HTML checkbox posts, "true" is what the
        // React form sends as JSON-ish form data.
        boolean termsAccept    = "on".equalsIgnoreCase(req.getParameter("termsAccept"))
                              || "true".equalsIgnoreCase(req.getParameter("termsAccept"));

        if (username == null) { badRequest(resp, "Username is required."); return; }
        String nameErr = InputValidator.getDisplayNameViolation(username);
        if (nameErr != null) { badRequest(resp, nameErr); return; }

        if (email == null) { badRequest(resp, "Email is required."); return; }
        String emailErr = InputValidator.getEmailFormatViolation(email.toLowerCase());
        if (emailErr != null) { badRequest(resp, emailErr); return; }

        String pwErr = InputValidator.getPasswordPolicyViolation(password);
        if (pwErr != null) { badRequest(resp, pwErr); return; }

        if (confirmPassword == null || !confirmPassword.equals(password)) {
            badRequest(resp, "Passwords do not match."); return;
        }
        if (!termsAccept) { badRequest(resp, "You must accept the terms to continue."); return; }

        // Buying and selling share one account, so sign-up offers no account-type
        // choice. Any `role` parameter on the request is ignored rather than trusted:
        // every registration creates a BUYER, who turns selling on in-app via
        // POST /api/account/enable-selling. ADMIN is never self-service.
        Role role = Role.BUYER;

        if (userDAO.checkEmail(email.toLowerCase())) {
            error(resp, 409, "An account with this email already exists.");
            return;
        }
        if (userDAO.checkUser(username)) {
            error(resp, 409, "Username already taken.");
            return;
        }

        User user = new User(username, email.toLowerCase(), SecurityUtil.hashPassword(password), role);
        try {
            boolean created = userDAO.insertUser(user);
            if (!created) {
                serverError(resp, "Registration failed. Please try again.");
                return;
            }
        } catch (RuntimeException e) {
            LOG.severe("Registration DB error: " + e.getMessage());
            serverError(resp,
                    "Could not reach the database. Ensure PostgreSQL is running and DBUtil settings are correct.");
            return;
        }

        okMsg(resp, "Account created. An administrator will review and approve it before you can sign in.");
        // Notified after the response is written, since fanning out email and Telegram alerts to
        // the admins must not make the user wait on registration.
        NotificationService.notifyAdminsPendingRegistration(username);
    }

    // ── Forgot Password ───────────────────────────────────────────────────────

    /**
     * POST /api/auth/forgot-password with {@code identifier}, the account email. Generates a
     * one-time code, stores it in {@link OtpStore} and emails it.
     *
     * <p>The reply is the same "if that account exists" message whether the email is registered
     * or not, so the endpoint cannot be used to test which addresses have accounts.</p>
     */
    private void handleForgot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String identifier = param(req, "identifier");
        if (identifier == null) { badRequest(resp, "Email is required."); return; }
        identifier = identifier.toLowerCase();

        String emailErr = InputValidator.getEmailFormatViolation(identifier);
        if (emailErr != null) { badRequest(resp, emailErr); return; }

        User user = userDAO.getUserByEmail(identifier);
        if (user == null) {
            // No such account: return the success wording anyway and send nothing.
            okMsg(resp, "If that account exists, an OTP has been sent.");
            return;
        }

        String otp = otpStore.generateAndStore(identifier);

        if (MailConfig.isSmtpConfigured()) {
            try {
                OtpMailer.sendPasswordResetCode(identifier, otp);
            } catch (MessagingException e) {
                LOG.warning("Failed to send reset email to " + identifier + ": " + e.getMessage());
                // The code was stored but never delivered, so drop it. Leaving it live would keep
                // a valid reset code sitting on the server that nobody can legitimately use.
                otpStore.invalidate(identifier);
                serverError(resp, "Could not send reset email. Check server SMTP settings.");
                return;
            }
        } else {
            LOG.warning("SMTP not configured — OTP for " + identifier + ": " + otp);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "If that account exists, an OTP has been sent.");
        if (DevMode.isEnabled()) body.put("devOtp", otp);
        ok(resp, body);
    }

    // ── Reset Password ────────────────────────────────────────────────────────

    /**
     * POST /api/auth/reset-password with {@code identifier}, {@code otp}, {@code newPassword}
     * and {@code confirmNewPassword}. The OTP is the only proof of ownership, so it is checked
     * before anything is written and burned immediately afterwards, making the code single use.
     */
    private void handleReset(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String identifier      = param(req, "identifier");
        String otp             = param(req, "otp");
        String newPassword     = req.getParameter("newPassword");
        String confirmPassword = req.getParameter("confirmNewPassword");

        if (identifier == null || otp == null) {
            badRequest(resp, "Identifier and OTP are required."); return;
        }
        String pwErr = InputValidator.getPasswordPolicyViolation(newPassword);
        if (pwErr != null) { badRequest(resp, pwErr); return; }
        if (!newPassword.equals(confirmPassword)) {
            badRequest(resp, "Passwords do not match."); return;
        }
        if (!otpStore.verify(identifier.toLowerCase(), otp)) {
            error(resp, 400, "Invalid or expired OTP."); return;
        }
        // Burn the code as soon as it verifies, so a replay of the same request fails.
        otpStore.invalidate(identifier.toLowerCase());
        boolean updated = userDAO.updatePassword(identifier.toLowerCase(), SecurityUtil.hashPassword(newPassword));
        if (!updated) { serverError(resp, "Failed to update password."); return; }
        okMsg(resp, "Password reset successfully.");
    }

    // ── Change Password ───────────────────────────────────────────────────────

    /**
     * POST /api/auth/change-password with {@code currentPassword}, {@code newPassword} and
     * {@code confirmPassword}. The only authenticated route in this servlet. The current
     * password is re-verified even though the caller holds a valid session, so someone who
     * walks up to an unlocked browser cannot take the account over.
     */
    private void handleChangePassword(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String currentPassword = req.getParameter("currentPassword");
        String newPassword     = req.getParameter("newPassword");
        String confirmPassword = req.getParameter("confirmPassword");

        User user = userDAO.getUserById(userId);
        if (user == null) { serverError(resp, "User not found."); return; }

        // getUserById intentionally omits the password column, so re-fetch by email
        // (which does select it) to verify the current password.
        User userWithPassword = userDAO.getUserByEmail(user.getEmail());
        if (userWithPassword == null
                || !SecurityUtil.verifyPassword(currentPassword, userWithPassword.getPassword())) {
            error(resp, 401, "Current password is incorrect."); return;
        }
        String pwErr = InputValidator.getPasswordPolicyViolation(newPassword);
        if (pwErr != null) { badRequest(resp, pwErr); return; }
        if (!newPassword.equals(confirmPassword)) {
            badRequest(resp, "Passwords do not match."); return;
        }

        boolean updated = userDAO.updatePassword(user.getEmail(), SecurityUtil.hashPassword(newPassword));
        if (!updated) { serverError(resp, "Failed to update password."); return; }

        // Force a fresh login on the new password. If the old one had leaked, whoever held it
        // would otherwise keep riding this session.
        TokenStore.getInstance().remove(bearerToken(req));
        okMsg(resp, "Password changed. Please log in again.");
    }
}
