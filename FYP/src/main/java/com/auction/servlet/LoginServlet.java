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

@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    static final String VIEW_LOGIN = "/WEB-INF/views/auth/login.jsp";

    private UserDAO userDAO;

    public LoginServlet() {
        userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher(VIEW_LOGIN).forward(req, resp);
    }

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

        HttpSession session = req.getSession(true);
        if ("1".equals(req.getParameter("rememberMe"))) {
            session.setMaxInactiveInterval(60 * 60 * 24 * 7);
        } else {
            session.setMaxInactiveInterval(60 * 30);
        }
        session.setAttribute("userId", user.getId());
        session.setAttribute("userRole", user.getRole().name());
        session.setAttribute("sessionEmail", user.getEmail());
        session.setAttribute("twoFactorEnabled", user.isTwoFactorEnabled());
        session.setAttribute("maskedEmail", SecurityUtil.maskEmail(user.getEmail()));
        session.setAttribute("maskedUsername", SecurityUtil.maskUsername(user.getUsername()));

        req.setAttribute("Login", "Login successful!");
        resp.sendRedirect(req.getContextPath() + "/protected/account");
    }

    private void loginError(HttpServletRequest req, String message, String email) {
        req.setAttribute("Error", message);
        req.setAttribute("email", email);
    }
}
