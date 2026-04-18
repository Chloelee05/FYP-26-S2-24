package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

public class ProfileServlet extends HttpServlet {

    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        User user = (session != null) ? (User) session.getAttribute("user") : null;
        if (user == null) {
            resp.sendRedirect(req.getContextPath() + "/auth/login");
            return;
        }
        User freshUser = userDAO.findById(user.getId());
        req.setAttribute("profile", freshUser);
        req.getRequestDispatcher("/auth/profile.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        User user = (session != null) ? (User) session.getAttribute("user") : null;
        if (user == null) {
            resp.sendRedirect(req.getContextPath() + "/auth/login");
            return;
        }

        user.setFullName(req.getParameter("fullName"));
        user.setEmail(req.getParameter("email"));
        user.setAddress(req.getParameter("address"));
        user.setPhone(req.getParameter("phone"));

        if (userDAO.update(user)) {
            session.setAttribute("user", user);
            req.setAttribute("success", "Profile updated successfully.");
        } else {
            req.setAttribute("error", "Failed to update profile.");
        }

        req.setAttribute("profile", user);
        req.getRequestDispatcher("/auth/profile.jsp").forward(req, resp);
    }
}
