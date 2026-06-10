package com.auction.servlet.api;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.util.InputValidator;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;
import com.auction.util.OtpStore;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import com.auction.util.TokenStore;
import jakarta.mail.MessagingException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles all auth API calls under /api/auth/*.
 *
 * POST /api/auth/login            params: email, password
 * POST /api/auth/logout
 * POST /api/auth/register         params: username, email, password, confirmPassword, role, termsAccept
 * POST /api/auth/forgot-password  params: identifier (email)
 * POST /api/auth/reset-password   params: identifier, otp, newPassword, confirmNewPassword
 * POST /api/auth/change-password  params: currentPassword, newPassword, confirmPassword
 */
@WebServlet("/api/auth/*")
public class AuthApiServlet extends ApiBase {

    private static final Logger LOG = Logger.getLogger(AuthApiServlet.class.getName());

    private final UserDAO  userDAO  = new UserDAO();
    private final OtpStore otpStore = new OtpStore();

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
    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String email    = param(req, "email");
        String password = req.getParameter("password");

        if (email == null) { badRequest(resp, "Email is required."); return; }
        if (password == null || password.isBlank()) { badRequest(resp, "Password is required."); return; }

        String emailErr = InputValidator.getEmailFormatViolation(email.toLowerCase());
        if (emailErr != null) { badRequest(resp, emailErr); return; }

        User user = userDAO.getUserByEmail(email.toLowerCase());
        if (user == null || !SecurityUtil.verifyPassword(password, user.getPassword())) {
            error(resp, 401, "Invalid email or password.");
            return;
        }
        if (user.getStatusId() == Status.SUSPENDED.getId()) {
            error(resp, 403, "Your account has been suspended.");
            return;
        }
        if (user.getStatusId() == Status.DELETED.getId()) {
            error(resp, 403, "This account is no longer available.");
            return;
        }

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
                LOG.warning("SMTP not configured — 2FA OTP for " + user.getEmail() + ": " + otp);
            }

            AuthSession pending = TokenStore.getInstance().create();
            pending.setMaxInactiveInterval(5 * 60);
            pending.setAttribute("awaitingTwoFactor", true);
            pending.setAttribute("pendingUserId",     user.getId());
            pending.setAttribute("pendingUserEmail",  user.getEmail());
            pending.setAttribute("pending2faOtp",     otp);

            Map<String, Object> twoFaBody = new LinkedHashMap<>();
            twoFaBody.put("requires2fa",  true);
            twoFaBody.put("pendingToken", pending.getToken());
            twoFaBody.put("maskedEmail",  SecurityUtil.maskEmail(user.getEmail()));
            if (!MailConfig.isSmtpConfigured()) twoFaBody.put("devOtp", otp);
            ok(resp, twoFaBody);
            return;
        }

        AuthSession session = TokenStore.getInstance().create();
        session.setMaxInactiveInterval(60 * 30);
        session.setAttribute("userId",           user.getId());
        session.setAttribute("userRole",         user.getRole().name());
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
        body.put("profileImageUrl", user.getProfileImageUrl());
        body.put("twoFactorEnabled", false);
        ok(resp, body);
    }

    // ── Logout ────────────────────────────────────────────────────────────────
    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        TokenStore.getInstance().remove(bearerToken(req));
        okMsg(resp, "Logged out.");
    }

    // ── Register ──────────────────────────────────────────────────────────────
    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username        = param(req, "username");
        String email           = param(req, "email");
        String password        = req.getParameter("password");
        String confirmPassword = req.getParameter("confirmPassword");
        String roleParam       = param(req, "role");
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

        Role role;
        try {
            role = (roleParam != null) ? Role.valueOf(roleParam.toUpperCase()) : Role.BUYER;
            if (role == Role.ADMIN) { badRequest(resp, "Invalid role."); return; }
        } catch (IllegalArgumentException e) {
            badRequest(resp, "Invalid role."); return;
        }

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

        okMsg(resp, "Account created successfully.");
    }

    // ── Forgot Password ───────────────────────────────────────────────────────
    private void handleForgot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String identifier = param(req, "identifier");
        if (identifier == null) { badRequest(resp, "Email is required."); return; }
        identifier = identifier.toLowerCase();

        String emailErr = InputValidator.getEmailFormatViolation(identifier);
        if (emailErr != null) { badRequest(resp, emailErr); return; }

        User user = userDAO.getUserByEmail(identifier);
        if (user == null) {
            okMsg(resp, "If that account exists, an OTP has been sent.");
            return;
        }

        String otp = otpStore.generateAndStore(identifier);

        if (MailConfig.isSmtpConfigured()) {
            try {
                OtpMailer.sendPasswordResetCode(identifier, otp);
            } catch (MessagingException e) {
                LOG.warning("Failed to send reset email to " + identifier + ": " + e.getMessage());
                otpStore.invalidate(identifier);
                serverError(resp, "Could not send reset email. Check server SMTP settings.");
                return;
            }
        } else {
            LOG.warning("SMTP not configured — OTP for " + identifier + ": " + otp);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "If that account exists, an OTP has been sent.");
        if (!MailConfig.isSmtpConfigured()) body.put("devOtp", otp);
        ok(resp, body);
    }

    // ── Reset Password ────────────────────────────────────────────────────────
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
        otpStore.invalidate(identifier.toLowerCase());
        boolean updated = userDAO.updatePassword(identifier.toLowerCase(), SecurityUtil.hashPassword(newPassword));
        if (!updated) { serverError(resp, "Failed to update password."); return; }
        okMsg(resp, "Password reset successfully.");
    }

    // ── Change Password ───────────────────────────────────────────────────────
    private void handleChangePassword(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        String currentPassword = req.getParameter("currentPassword");
        String newPassword     = req.getParameter("newPassword");
        String confirmPassword = req.getParameter("confirmPassword");

        User user = userDAO.getUserById(userId);
        if (user == null) { serverError(resp, "User not found."); return; }

        if (!SecurityUtil.verifyPassword(currentPassword, user.getPassword())) {
            error(resp, 401, "Current password is incorrect."); return;
        }
        String pwErr = InputValidator.getPasswordPolicyViolation(newPassword);
        if (pwErr != null) { badRequest(resp, pwErr); return; }
        if (!newPassword.equals(confirmPassword)) {
            badRequest(resp, "Passwords do not match."); return;
        }

        boolean updated = userDAO.updatePassword(user.getEmail(), SecurityUtil.hashPassword(newPassword));
        if (!updated) { serverError(resp, "Failed to update password."); return; }

        TokenStore.getInstance().remove(bearerToken(req));
        okMsg(resp, "Password changed. Please log in again.");
    }
}
