package com.auction.servlet.admin;

import java.io.IOException;

import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/admin/users/action")
public class AdminManageUserServlet extends HttpServlet {

    private UserDAO userDAO;

    public AdminManageUserServlet() {
        userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.sendRedirect(req.getContextPath() + "/admin/users");
    }

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
                ok = userDAO.updateStatus(targetUserId, Status.SUSPENDED.getId());
                message = ok ? "Account successfully suspended!" : "Could not suspend account.";
                if (!ok) {
                    flashKey = "adminFlashError";
                }
                break;
            case "active":
                ok = userDAO.updateStatus(targetUserId, Status.ACTIVE.getId());
                message = ok ? "Account successfully unsuspended!" : "Could not reactivate account.";
                if (!ok) {
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
