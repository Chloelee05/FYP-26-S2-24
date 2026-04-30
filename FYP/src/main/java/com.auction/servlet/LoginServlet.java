package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

public class LoginServlet extends HttpServlet {

    private UserDAO userDAO;

    public LoginServlet() {
        userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String email = req.getParameter("email");
        String password = req.getParameter("password");

        email = (email == null) ? null : email.trim().toLowerCase();

        String emailViolation = InputValidator.getEmailFormatViolation(email);
        if (emailViolation != null) {
            loginError(req, emailViolation, email);
            return;
        }

        if (password == null || password.isBlank()) {
            loginError(req, "Password is required.", email);
            return;
        }

        User user = userDAO.getUserByEmail(email);
        if (user == null) {
            loginError(req, "Invalid email or password.", email);
            return;
        }

        if (!SecurityUtil.verifyPassword(password, user.getPassword())) {
            loginError(req, "Invalid email or password.", email);
            return;
        }

        HttpSession session = req.getSession(true);
        session.setAttribute("userId", user.getId());
        session.setAttribute("userRole", user.getRole().name());
        session.setAttribute("sessionEmail", user.getEmail());
        session.setAttribute("twoFactorEnabled", user.isTwoFactorEnabled());
        session.setAttribute("maskedEmail", SecurityUtil.maskEmail(user.getEmail()));
        session.setAttribute("maskedUsername", SecurityUtil.maskUsername(user.getUsername()));

        req.setAttribute("Login", "Login successful!");
    }

    private void loginError(HttpServletRequest req, String message, String email) {
        req.setAttribute("Error", message);
        req.setAttribute("email", email);
    }
}
