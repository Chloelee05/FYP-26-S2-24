package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.InputValidator;
import com.auction.util.OtpStore;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Step 1 of the password-reset flow.
 * Accepts an email address or phone number, validates it, looks up the account,
 * generates a 6-digit OTP via {@link OtpStore}, and simulates delivery.
 *
 * In production, replace the simulated-delivery block with a real email/SMS API call
 * and remove the {@code simulatedOtp} response attribute.
 */
public class ForgotPasswordServlet extends HttpServlet {

    private UserDAO userDAO;
    private OtpStore otpStore;

    public ForgotPasswordServlet() {
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
        identifier = (identifier == null) ? null : identifier.trim().toLowerCase();

        if (identifier == null || identifier.isBlank()) {
            req.setAttribute("Error", "Email or phone number is required.");
            return;
        }

        // Route to email or phone validation based on presence of '@'
        boolean isEmail = identifier.contains("@");
        if (isEmail) {
            String emailViolation = InputValidator.getEmailFormatViolation(identifier);
            if (emailViolation != null) {
                req.setAttribute("Error", emailViolation);
                req.setAttribute("identifier", identifier);
                return;
            }
        } else {
            String phoneViolation = InputValidator.getPhoneFormatViolation(identifier);
            if (phoneViolation != null) {
                req.setAttribute("Error", phoneViolation);
                req.setAttribute("identifier", identifier);
                return;
            }
        }

        // Look up account — email only for now; phone requires a phone column in the user table
        User user = isEmail ? userDAO.getUserByEmail(identifier) : null;

        // Always show a generic message to prevent account enumeration
        if (user == null) {
            req.setAttribute("OtpSent", "If that account exists, an OTP has been sent.");
            return;
        }

        // Generate OTP and simulate delivery
        String otp = otpStore.generateAndStore(identifier);

        // Simulated delivery — replace with email/SMS API in production
        req.setAttribute("simulatedOtp", otp);
        req.setAttribute("OtpSent", "If that account exists, an OTP has been sent.");
        req.setAttribute("resetIdentifier", identifier);
    }
}
