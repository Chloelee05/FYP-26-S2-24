package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Signed-in user's account dashboard. Loads the profile by {@code session.userId} only
 * (never by request parameters) so another user's row cannot be targeted.
 */
@WebServlet("/protected/account")
public class AccountManagementServlet extends HttpServlet {

    public static final String VIEW_DASHBOARD = "/WEB-INF/views/account/dashboard.jsp";

    private UserDAO userDAO;

    public AccountManagementServlet() {
        this.userDAO = new UserDAO();
    }

    /** For unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        Integer userId = readUserId(session);
        if (userId == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        User profile = userDAO.getUserById(userId);
        if (profile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found");
            return;
        }

        if (!isProfileOwnedBySession(session, profile)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
            return;
        }

        req.setAttribute("profileUsername", SecurityUtil.sanitize(profile.getUsername()));
        req.setAttribute("profileEmail", profile.getEmail());
        req.setAttribute("profileRole", profile.getRole().name());
        req.setAttribute("twoFactorEnabled", profile.isTwoFactorEnabled());

        req.setAttribute("profilePhone", decryptPiiForDisplay(profile.getPhoneEncrypted()));
        req.setAttribute("profileAddress", decryptPiiForDisplay(profile.getAddressEncrypted()));

        req.getRequestDispatcher(VIEW_DASHBOARD).forward(req, resp);
    }

    static Integer readUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object raw = session.getAttribute("userId");
        if (raw instanceof Integer) {
            return (Integer) raw;
        }
        if (raw instanceof Long) {
            return ((Long) raw).intValue();
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        return null;
    }

    private static boolean isProfileOwnedBySession(HttpSession session, User profile) {
        Integer sessionUserId = readUserId(session);
        return sessionUserId != null && sessionUserId == profile.getId();
    }

    static String decryptPiiForDisplay(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            return SecurityUtil.decrypt(ciphertext);
        } catch (SecurityUtil.SecurityOperationException e) {
            return null;
        }
    }
}
