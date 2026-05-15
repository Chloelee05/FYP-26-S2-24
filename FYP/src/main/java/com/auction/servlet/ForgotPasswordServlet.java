package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.InputValidator;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;
import com.auction.util.OtpStore;

import jakarta.mail.MessagingException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Step 1 of the password-reset flow.
 * Accepts an email address or phone number, validates it, looks up the account,
 * generates a 6-digit OTP via {@link OtpStore}, then sends it by SMTP when
 * {@link MailConfig#isSmtpConfigured()} is true ({@code AUCTION_SMTP_HOST}, etc.).
 * Otherwise the OTP is exposed only as {@code simulatedOtp} for local development.
 */
@WebServlet("/forgot-password")
public class ForgotPasswordServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ForgotPasswordServlet.class.getName());

    static final String VIEW_FORGOT = "/WEB-INF/views/auth/forgot-password.jsp";
    static final String VIEW_RESET = "/WEB-INF/views/auth/reset-password.jsp";

    private UserDAO userDAO;
    private OtpStore otpStore;

    public ForgotPasswordServlet() {
        userDAO = new UserDAO();
        otpStore = new OtpStore();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    public void setOtpStore(OtpStore otpStore) {
        this.otpStore = otpStore;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = req.getParameter("identifier");
        identifier = (identifier == null) ? null : identifier.trim().toLowerCase();

        if (identifier == null || identifier.isBlank()) {
            req.setAttribute("Error", "Email or phone number is required.");
            req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
            return;
        }

        boolean isEmail = identifier.contains("@");
        if (isEmail) {
            String emailViolation = InputValidator.getEmailFormatViolation(identifier);
            if (emailViolation != null) {
                req.setAttribute("Error", emailViolation);
                req.setAttribute("identifier", identifier);
                req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
                return;
            }
        } else {
            String phoneViolation = InputValidator.getPhoneFormatViolation(identifier);
            if (phoneViolation != null) {
                req.setAttribute("Error", phoneViolation);
                req.setAttribute("identifier", identifier);
                req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
                return;
            }
        }

        User user = isEmail ? userDAO.getUserByEmail(identifier) : null;

        if (user == null) {
            req.setAttribute("OtpSent", "If that account exists, an OTP has been sent.");
            req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
            return;
        }

        String otp = otpStore.generateAndStore(identifier);

        if (MailConfig.isSmtpConfigured()) {
            try {
                OtpMailer.sendPasswordResetCode(identifier, otp);
            } catch (MessagingException e) {
                LOG.log(Level.WARNING, "Failed to send password-reset email to " + identifier, e);
                otpStore.invalidate(identifier);
                req.setAttribute("Error",
                        "We could not send the reset email. Check SMTP settings (AUCTION_SMTP_*) on the server.");
                req.setAttribute("identifier", identifier);
                req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
                return;
            }
        } else {
            LOG.warning("AUCTION_SMTP_HOST not set — password reset OTP for " + identifier + ": " + otp);
            req.setAttribute("simulatedOtp", otp);
        }

        req.setAttribute("OtpSent", "If that account exists, an OTP has been sent.");
        req.setAttribute("resetIdentifier", identifier);
        req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
    }
}
