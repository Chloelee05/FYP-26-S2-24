package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * SCRUM-12: Authenticated user changes password — verifies current hash, stores new via
 * {@link SecurityUtil#hashPassword(String)} (salted SHA-256), then invalidates the session
 * so the user must sign in again (SCRUM-197).
 *
 * <p>Legacy JSP page behind {@code AuthFilter}. The SPA posts to
 * {@code /api/auth/change-password} in {@code AuthApiServlet} instead.</p>
 *
 * <p>Two rules here are worth noticing. The account is reloaded by the session's email and then
 * cross-checked against the session's user id, so a stale or tampered session cannot be used to
 * change a different account's password. And the session is destroyed on success, which forces
 * a fresh sign-in and closes any other session that was open on the old password.</p>
 */
@WebServlet("/protected/account/password")
public class ChangePasswordServlet extends HttpServlet {

    public static final String VIEW_FORM = "/WEB-INF/views/account/change-password.jsp";

    private UserDAO userDAO;

    public ChangePasswordServlet() {
        this.userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** Shows the change-password form to a signed-in user. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (!hasAuthenticatedSession(session)) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }
        req.getRequestDispatcher(VIEW_FORM).forward(req, resp);
    }

    /**
     * Applies the change. Expects {@code currentPassword}, {@code newPassword} and
     * {@code confirmPassword}. Cheap validation runs first (presence, policy, match, and that
     * the new password differs from the old), so the database is only touched once the input is
     * worth checking. Ends by invalidating the session and redirecting to the login page with a
     * flag the form uses to show a confirmation message.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (!hasAuthenticatedSession(session)) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        Integer userId = AccountManagementServlet.readUserId(session);
        String sessionEmail = readSessionEmail(session);
        if (userId == null || sessionEmail == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        String current = req.getParameter("currentPassword");
        String newPw = req.getParameter("newPassword");
        String confirm = req.getParameter("confirmPassword");

        if (current == null || current.isBlank()) {
            forwardError(req, resp, "Current password is required.");
            return;
        }
        if (newPw == null || newPw.isBlank()) {
            forwardError(req, resp, "New password is required.");
            return;
        }
        if (confirm == null || confirm.isBlank()) {
            forwardError(req, resp, "Please confirm your new password.");
            return;
        }

        String policyError = InputValidator.getPasswordPolicyViolation(newPw);
        if (policyError != null) {
            forwardError(req, resp, policyError);
            return;
        }
        if (!newPw.equals(confirm)) {
            forwardError(req, resp, "New password and confirmation do not match.");
            return;
        }
        if (current.equals(newPw)) {
            forwardError(req, resp, "New password must differ from your current password.");
            return;
        }

        // The row is fetched by the session's email, then its id must match the session's id.
        // Both attributes were written at login, so a disagreement means the session no longer
        // describes a single real account and the request is refused rather than guessed at.
        User user = userDAO.getUserByEmail(sessionEmail);
        if (user == null || user.getId() != userId) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Session does not match account.");
            return;
        }

        if (!SecurityUtil.verifyPassword(current, user.getPassword())) {
            forwardError(req, resp, "Current password is incorrect.");
            return;
        }

        String hashed = SecurityUtil.hashPassword(newPw);
        if (!userDAO.updatePassword(user.getEmail(), hashed)) {
            forwardError(req, resp, "Could not update password. Please try again.");
            return;
        }

        // Forcing a re-login is the point of SCRUM-197: any other session that was opened with
        // the old password dies with this one.
        session.invalidate();

        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);

        resp.sendRedirect(req.getContextPath() + "/login?passwordChanged=1");
    }

    /** True when the session carries a usable user id. */
    private static boolean hasAuthenticatedSession(HttpSession session) {
        return session != null && AccountManagementServlet.readUserId(session) != null;
    }

    /**
     * Reads {@code sessionEmail} from the session, normalising a blank or non-String value to
     * {@code null}. Shared with the other account servlets in this package.
     */
    static String readSessionEmail(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object raw = session.getAttribute("sessionEmail");
        if (!(raw instanceof String)) {
            return null;
        }
        String s = ((String) raw).trim();
        return s.isEmpty() ? null : s;
    }

    /** Redisplays the form with an error banner. */
    private static void forwardError(HttpServletRequest req, HttpServletResponse resp, String message)
            throws ServletException, IOException {
        req.setAttribute("error", message);
        req.getRequestDispatcher(VIEW_FORM).forward(req, resp);
    }
}
