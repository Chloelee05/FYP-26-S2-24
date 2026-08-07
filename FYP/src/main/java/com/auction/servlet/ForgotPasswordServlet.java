package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.DevMode;
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
 * Otherwise the OTP is written to the server log, and rendered on the page as
 * {@code simulatedOtp} only when {@link DevMode#isEnabled()}.
 *
 * <p>That last condition is a security gate, not a convenience. Putting the code in the HTTP
 * response makes testing possible without a mail server, but anyone who can reach the form
 * could then reset an account they do not own. {@link DevMode} reads the {@code AUCTION_DEV_MODE}
 * flag, which is off in production, so the code can never travel back to the browser there.</p>
 *
 * <p>This is the legacy JSP half of the reset flow. The SPA posts to
 * {@code /api/auth/forgot-password} in {@code AuthApiServlet} for the same feature.</p>
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

    /** Shows the form that asks for the account's email or phone number. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher(VIEW_FORGOT).forward(req, resp);
    }

    /**
     * Reads the {@code identifier} field, decides from the presence of an "@" whether to treat
     * it as an email or a phone number, validates it in the matching format, and issues an OTP
     * when the account exists. On success the user is forwarded to the reset form with the
     * identifier carried across; the OTP itself lives in {@link OtpStore}, not in the page.
     */
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

        // An unknown identifier gets exactly the same "if that account exists" wording as a real
        // one. The form must not become a way of testing which emails are registered, so the
        // response is identical whether or not a lookup succeeded.
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
                // The stored code is thrown away when the mail fails. Leaving a live OTP behind
                // that nobody received would only widen the window for guessing it.
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
            if (DevMode.isEnabled()) {
                req.setAttribute("simulatedOtp", otp);
            }
        }

        req.setAttribute("OtpSent", "If that account exists, an OTP has been sent.");
        req.setAttribute("resetIdentifier", identifier);
        req.getRequestDispatcher(VIEW_RESET).forward(req, resp);
    }
}
