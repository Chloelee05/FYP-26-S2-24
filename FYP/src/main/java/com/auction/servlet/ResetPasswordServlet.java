package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.util.InputValidator;
import com.auction.util.OtpStore;
import com.auction.util.SecurityUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Step 2 of the password-reset flow.
 * Receives the identifier (email/phone), the OTP entered by the user, and the new password.
 * Validates all fields, verifies the OTP via {@link OtpStore}, hashes the new password
 * using {@link SecurityUtil#hashPassword(String)} (salted SHA-256), updates the DB,
 * and invalidates the OTP to prevent replay.
 */
public class ResetPasswordServlet extends HttpServlet {

    private UserDAO userDAO;
    private OtpStore otpStore;

    public ResetPasswordServlet() {
        userDAO = new UserDAO();
        otpStore = new OtpStore();
    }

    public void setUserDAO(UserDAO userDAO) { this.userDAO = userDAO; }
    public void setOtpStore(OtpStore otpStore) { this.otpStore = otpStore; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = req.getParameter("identifier");
        String otp        = req.getParameter("otp");
        String newPassword = req.getParameter("newPassword");

        identifier = (identifier == null) ? null : identifier.trim().toLowerCase();
        otp        = (otp == null)        ? null : otp.trim();

        // Guard: identifier must be present (carried as hidden form field from step 1)
        if (identifier == null || identifier.isBlank()) {
            req.setAttribute("Error", "Session expired. Please restart the password-reset flow.");
            return;
        }

        // Validate OTP field is non-empty before touching the store
        if (otp == null || otp.isBlank()) {
            req.setAttribute("Error", "OTP is required.");
            req.setAttribute("identifier", identifier);
            return;
        }

        // Validate new password policy before making any external calls
        String passwordViolation = InputValidator.getPasswordPolicyViolation(newPassword);
        if (passwordViolation != null) {
            req.setAttribute("Error", passwordViolation);
            req.setAttribute("identifier", identifier);
            return;
        }

        // Verify OTP — covers wrong code AND expiry (OtpStore evicts expired entries)
        if (!otpStore.verify(identifier, otp)) {
            req.setAttribute("Error", "Invalid or expired OTP.");
            req.setAttribute("identifier", identifier);
            return;
        }

        // Hash new password with SecurityUtil (salted SHA-256, format "1$salt$hash")
        String hashedPassword = SecurityUtil.hashPassword(newPassword);

        if (!userDAO.updatePassword(identifier, hashedPassword)) {
            req.setAttribute("Error", "Password update failed. Please try again.");
            return;
        }

        // Invalidate OTP immediately after a successful reset to prevent replay
        otpStore.invalidate(identifier);

        req.setAttribute("Reset", "Password reset successfully!");
    }
}
