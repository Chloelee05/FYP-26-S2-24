package com.auction.servlet;

import com.auction.dao.UserDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Self-service account closure (SCRUM-9). Anonymises PII via {@link UserDAO#deleteAccount(int)},
 * then invalidates the session so no further authenticated requests are possible.
 */
@WebServlet("/protected/account/delete")
public class DeleteAccountServlet extends HttpServlet {

    /** Must match the hidden field posted from the confirmation modal. */
    public static final String CONFIRM_TOKEN = "DELETE";

    private UserDAO userDAO;

    public DeleteAccountServlet() {
        this.userDAO = new UserDAO();
    }

    /** For unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        Integer userId = AccountManagementServlet.readUserId(session);
        if (userId == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        String confirm = req.getParameter("confirm");
        if (!CONFIRM_TOKEN.equals(confirm)) {
            resp.sendRedirect(req.getContextPath() + "/protected/account");
            return;
        }

        boolean removed = userDAO.deleteAccount(userId);
        if (!removed) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found");
            return;
        }

        if (session != null) {
            session.invalidate();
        }

        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);

        resp.sendRedirect(req.getContextPath() + "/login?accountClosed=1");
    }
}
