package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.util.InputValidator;
import com.auction.util.OtpStore;
import com.auction.util.SecurityUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
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
 *
 * <p>Ordering here is deliberate: the new password is checked against the policy and the
 * confirmation field before the OTP is verified, so a wasted OTP attempt is not spent on a
 * typo. The code is only consumed once the update has actually succeeded.</p>
 *
 * <p>Legacy JSP flow. The SPA posts to {@code /api/auth/reset-password} in
 * {@code AuthApiServlet} for the same step.</p>
 */
@WebServlet("/reset-password")
public class ResetPasswordServlet extends HttpServlet {

    static final String VIEW_RESET = "/WEB-INF/views/auth/reset-password.jsp";
    static final String VIEW_FORGOT = "/WEB-INF/views/auth/forgot-password.jsp";

    private UserDAO userDAO;
    private OtpStore otpStore;

    public ResetPasswordServlet() {
        userDAO = new UserDAO();
        otpStore = new OtpStore();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    public void setOtpStore(OtpStore otpStore) {
        this.otpStore = otpStore;
    }

    /**
     * A direct GET means somebody reached this URL without going through step one, so there is
     * no identifier to reset against. Sends them back to the forgot-password form.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setAttribute("Error", "Please use the forgot-password form to request a reset code.");
        req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
    }

    /**
     * Completes the reset. Expects {@code identifier}, {@code otp}, {@code newPassword} and
     * {@code confirmNewPassword}. The identifier travels in a hidden form field rather than the
     * session, so a missing value means the flow was restarted or the page was opened directly.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = req.getParameter("identifier");
        String otp = req.getParameter("otp");
        String newPassword = req.getParameter("newPassword");
        String confirmPassword = req.getParameter("confirmNewPassword");

        identifier = (identifier == null) ? null : identifier.trim().toLowerCase();
        otp = (otp == null) ? null : otp.trim();

        if (identifier == null || identifier.isBlank()) {
            req.setAttribute("Error", "Session expired. Please restart the password-reset flow.");
            req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
            return;
        }

        req.setAttribute("resetIdentifier", identifier);

        if (otp == null || otp.isBlank()) {
            req.setAttribute("Error", "OTP is required.");
            req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
            return;
        }

        String passwordViolation = InputValidator.getPasswordPolicyViolation(newPassword);
        if (passwordViolation != null) {
            req.setAttribute("Error", passwordViolation);
            req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
            return;
        }

        if (newPassword == null || confirmPassword == null || !newPassword.equals(confirmPassword)) {
            req.setAttribute("Error", "Passwords do not match.");
            req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
            return;
        }

        if (!otpStore.verify(identifier, otp)) {
            req.setAttribute("Error", "Invalid or expired OTP.");
            req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
            return;
        }

        String hashedPassword = SecurityUtil.hashPassword(newPassword);

        if (!userDAO.updatePassword(identifier, hashedPassword)) {
            req.setAttribute("Error", "Password update failed. Please try again.");
            req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
            return;
        }

        // Burn the code only after the password has really changed, so a failed update leaves
        // the user able to retry with the same OTP instead of starting the whole flow again.
        otpStore.invalidate(identifier);

        req.setAttribute("Reset", "Password reset successfully!");
        req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
    }
}