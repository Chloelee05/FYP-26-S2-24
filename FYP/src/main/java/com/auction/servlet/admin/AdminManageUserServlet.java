package com.auction.servlet.admin;

import java.io.IOException;
import java.util.logging.Logger;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Applies ban and unban actions to a user account, then redirects back to the admin user table
 * with a flash message. The write-side companion to {@link AdminUsersServlet}.
 *
 * <p>Legacy JSP admin console behind {@code AdminFilter}; the SPA performs the same action
 * through {@code /api/admin/*} in {@code AdminApiServlet}.</p>
 *
 * <p>Three guards apply before anything changes. An admin cannot act on their own account,
 * which is what stops someone locking themselves out. Admin accounts cannot be banned from this
 * screen at all. And each action checks the current status first, so banning an already banned
 * user is refused rather than quietly repeated. Successful actions are logged with the admin id,
 * the target id and a sanitized username, forming the audit trail.</p>
 */
@WebServlet("/admin/users/action")
public class AdminManageUserServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(AdminManageUserServlet.class.getName());

    private UserDAO userDAO;

    public AdminManageUserServlet() {
        userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** This endpoint only accepts posts, so a typed URL is sent back to the user table. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.sendRedirect(req.getContextPath() + "/admin/users");
    }

    /**
     * Performs one moderation action. Expects {@code action}, one of "suspend", "active" or
     * "unban", and {@code userid}. Ends with a redirect in every case so the browser cannot
     * repeat the action on refresh; the outcome travels back as a flash message.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String action = req.getParameter("action");
        String userid = req.getParameter("userid");
        if (action == null || action.isBlank() || userid == null || userid.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // SCRUM-279: userid is always parsed as an integer — non-numeric input is rejected
        // with 400 before any DB lookup, preventing IDOR via crafted strings.
        int targetUserId;
        try {
            targetUserId = Integer.parseInt(userid.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        Integer adminId = (Integer) session.getAttribute("userId");
        if (adminId != null && adminId == targetUserId) {
            session.setAttribute("adminFlashError", "You cannot change your own account status.");
            resp.sendRedirect(req.getContextPath() + "/admin/users");
            return;
        }

        // SCRUM-279: target user is loaded by server-side ID; no client-supplied role or
        // status value is trusted — all authorization decisions are based on the DB row.
        User target = userDAO.getUserById(targetUserId);
        if (target == null) {
            session.setAttribute("adminFlashError", "User not found.");
            resp.sendRedirect(req.getContextPath() + "/admin/users");
            return;
        }
        if (target.getRole() == Role.ADMIN) {
            session.setAttribute("adminFlashError", "Admin accounts cannot be banned or unbanned here.");
            resp.sendRedirect(req.getContextPath() + "/admin/users");
            return;
        }

        action = action.toLowerCase();
        String flashKey = "adminFlash";
        String message;
        boolean ok;
        switch (action) {
            case "suspend":
                // SCRUM-212: guard against redundant ban
                if (target.getStatusId() == Status.SUSPENDED.getId()) {
                    session.setAttribute("adminFlashError", "User account is already banned.");
                    resp.sendRedirect(req.getContextPath() + "/admin/users");
                    return;
                }
                ok = userDAO.updateStatus(targetUserId, Status.SUSPENDED.getId());
                message = ok ? "Account successfully banned." : "Could not ban account.";
                if (ok) {
                    // SCRUM-213: audit trail — sanitize username before logging (SCRUM-279)
                    LOGGER.info(String.format("Admin [id=%d] banned user [id=%d, username=%s].",
                            adminId, targetUserId, SecurityUtil.sanitize(target.getUsername())));
                } else {
                    flashKey = "adminFlashError";
                }
                break;
            case "active":
            case "unban":
                // SCRUM-212: guard — only SUSPENDED accounts may be unbanned
                if (target.getStatusId() != Status.SUSPENDED.getId()) {
                    session.setAttribute("adminFlashError", "User account is not currently banned.");
                    resp.sendRedirect(req.getContextPath() + "/admin/users");
                    return;
                }
                ok = userDAO.updateStatus(targetUserId, Status.ACTIVE.getId());
                message = ok ? "Account successfully unbanned." : "Could not unban account.";
                if (ok) {
                    // SCRUM-213: audit trail — sanitize username before logging (SCRUM-279)
                    LOGGER.info(String.format("Admin [id=%d] unbanned user [id=%d, username=%s].",
                            adminId, targetUserId, SecurityUtil.sanitize(target.getUsername())));
                } else {
                    flashKey = "adminFlashError";
                }
                break;
            default:
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
        }

        session.setAttribute(flashKey, message);
        resp.sendRedirect(req.getContextPath() + "/admin/users");
    }
}
