package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
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
 * Legacy JSP sign-in page. Renders {@code /WEB-INF/views/auth/login.jsp} on GET and verifies
 * the credentials on POST, then builds the session that {@code RbacUtil}, {@code AuthFilter}
 * and {@code AdminFilter} all read from afterwards.
 *
 * <p>The React SPA does not use this servlet; it posts to {@code /api/auth/login} in
 * {@code AuthApiServlet} instead. Both paths remain mapped, so the same account can sign in
 * through either one.</p>
 *
 * <p>Collaborators: {@link UserDAO} for the account row, {@link SecurityUtil} for password
 * verification against the salted SHA-256 hash and for the masked display values kept in the
 * session, {@link InputValidator} for the email format check.</p>
 */
@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    static final String VIEW_LOGIN = "/WEB-INF/views/auth/login.jsp";

    private UserDAO userDAO;

    public LoginServlet() {
        userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** Shows the sign-in form. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
    }

    /**
     * Validates the submitted email and password, then either redisplays the form with an error
     * or establishes the session and redirects by role: admins to the admin dashboard, everyone
     * else to their account page. Reads {@code email}, {@code password} and the optional
     * {@code rememberMe} flag from the form.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String email = req.getParameter("email");
        String password = req.getParameter("password");

        email = (email == null) ? null : email.trim().toLowerCase();

        String emailViolation = InputValidator.getEmailFormatViolation(email);
        if (emailViolation != null) {
            loginError(req, emailViolation, email);
            req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
            return;
        }

        if (password == null || password.isBlank()) {
            loginError(req, "Password is required.", email);
            req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
            return;
        }

        // An unknown email and a wrong password deliberately produce the same message, so the
        // form cannot be used to work out which addresses have accounts on the platform.
        User user = userDAO.getUserByEmail(email);
        if (user == null) {
            loginError(req, "Invalid email or password.", email);
            req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
            return;
        }

        if (!SecurityUtil.verifyPassword(password, user.getPassword())) {
            loginError(req, "Invalid email or password.", email);
            req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
            return;
        }

        if (user.getStatusId() == Status.SUSPENDED.getId()) {
            loginError(req, "Your account has been suspended.", email);
            req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
            return;
        }
        if (user.getStatusId() == Status.DELETED.getId()) {
            loginError(req, "This account is no longer available.", email);
            req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
            return;
        }

        // Everything the request-time authorisation checks need is copied into the session here:
        // userId for ownership tests, userRole for RBAC, and canSell because one account can both
        // buy and sell. The masked name and email are stored ready-made so pages that display
        // them never handle the raw PDPA-sensitive values.
        HttpSession session = req.getSession(true);
        if ("1".equals(req.getParameter("rememberMe"))) {
            session.setMaxInactiveInterval(60 * 60 * 24 * 7);
        } else {
            session.setMaxInactiveInterval(60 * 30);
        }
        session.setAttribute("userId", user.getId());
        session.setAttribute("userRole", user.getRole().name());
        session.setAttribute("canSell", user.canSell());
        session.setAttribute("sessionEmail", user.getEmail());
        session.setAttribute("twoFactorEnabled", user.isTwoFactorEnabled());
        session.setAttribute("maskedEmail", SecurityUtil.maskEmail(user.getEmail()));
        session.setAttribute("maskedUsername", SecurityUtil.maskUsername(user.getUsername()));

        req.setAttribute("Login", "Login successful!");
        String target = user.getRole() == Role.ADMIN
                ? "/admin/dashboard"
                : "/protected/account";
        resp.sendRedirect(req.getContextPath() + target);
    }

    /** Sets the error banner and keeps the typed email so the form is not blank on redisplay. */
    private void loginError(HttpServletRequest req, String message, String email) {
        req.setAttribute("Error", message);
        req.setAttribute("email", email);
    }
}
