package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

public class LoginServlet extends HttpServlet {

    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (req.getSession(false) != null && req.getSession().getAttribute("user") != null) {
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }
        req.getRequestDispatcher("/auth/login.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");

        if (username == null || username.trim().isEmpty() ||
            password == null || password.trim().isEmpty()) {
            req.setAttribute("error", "Please fill in all fields.");
            req.getRequestDispatcher("/auth/login.jsp").forward(req, resp);
            return;
        }

        User user = userDAO.findByUsername(username.trim());
        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            req.setAttribute("error", "Invalid username or password.");
            req.setAttribute("username", username);
            req.getRequestDispatcher("/auth/login.jsp").forward(req, resp);
            return;
        }

        if ("SUSPENDED".equals(user.getStatus())) {
            req.setAttribute("error", "Your account has been suspended. Please contact admin.");
            req.getRequestDispatcher("/auth/login.jsp").forward(req, resp);
            return;
        }

        HttpSession session = req.getSession(true);
        session.setAttribute("user", user);
        resp.sendRedirect(req.getContextPath() + "/");
    }
}
