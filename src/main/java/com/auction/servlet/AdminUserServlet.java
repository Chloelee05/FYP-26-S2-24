package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

public class AdminUserServlet extends HttpServlet {

    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        List<User> users = userDAO.findAll();
        req.setAttribute("users", users);
        req.getRequestDispatcher("/admin/users.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String action = req.getParameter("action");
        String userIdStr = req.getParameter("userId");

        if (action != null && userIdStr != null) {
            int userId = Integer.parseInt(userIdStr);
            switch (action) {
                case "activate":
                    userDAO.updateStatus(userId, "ACTIVE");
                    break;
                case "suspend":
                    userDAO.updateStatus(userId, "SUSPENDED");
                    break;
                case "approve":
                    userDAO.updateStatus(userId, "ACTIVE");
                    break;
            }
        }

        resp.sendRedirect(req.getContextPath() + "/admin/users");
    }
}
