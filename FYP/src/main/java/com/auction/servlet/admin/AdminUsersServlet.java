package com.auction.servlet.admin;

import com.auction.dao.UserDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Renders the admin user table. Read-only: banning and unbanning are handled by
 * {@link AdminManageUserServlet} at {@code /admin/users/action}, which redirects back here.
 *
 * <p>Legacy JSP admin console behind {@code AdminFilter}; the SPA reads users from
 * {@code /api/admin/*}. The listing comes from {@link UserDAO#listUsersForAdminTable}, which
 * selects display columns only rather than the full user rows.</p>
 */
@WebServlet("/admin/users")
public class AdminUsersServlet extends HttpServlet {

    private UserDAO userDAO;

    public AdminUsersServlet() {
        userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** Loads the user list and shows any message left behind by the action servlet. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession();
        copyFlash(session, req, "adminFlash");
        copyFlash(session, req, "adminFlashError");

        req.setAttribute("users", userDAO.listUsersForAdminTable());
        req.setAttribute("adminActiveNav", "users");
        req.getRequestDispatcher("/WEB-INF/views/admin/users.jsp").forward(req, resp);
    }

    /**
     * Moves a one-shot message from the session onto the request and clears it, so a message
     * created before a redirect is displayed exactly once.
     */
    private static void copyFlash(HttpSession session, HttpServletRequest req, String key) {
        Object v = session.getAttribute(key);
        if (v != null) {
            req.setAttribute(key, v);
            session.removeAttribute(key);
        }
    }
}
