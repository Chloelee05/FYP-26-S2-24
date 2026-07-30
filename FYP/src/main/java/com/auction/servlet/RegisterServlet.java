package com.auction.servlet;

import java.io.IOException;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.model.Role;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/register")
public class RegisterServlet extends HttpServlet {

    static final String VIEW_REGISTER = "/WEB-INF/views/auth/register.jsp";

    private UserDAO userDAO;

    public RegisterServlet() {
        userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String username = req.getParameter("username");
        String email = req.getParameter("email");
        String password = req.getParameter("password");
        String confirmPassword = req.getParameter("confirmPassword");

        username = (username == null) ? null : username.trim();
        email = (email == null) ? null : email.trim().toLowerCase();

        if (username == null || username.isBlank()) {
            errorHandler(req, "Username is required.", username, email, confirmPassword);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        String displayNameViolation = InputValidator.getDisplayNameViolation(username);
        if (displayNameViolation != null) {
            errorHandler(req, displayNameViolation, username, email, confirmPassword);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        String emailViolation = InputValidator.getEmailFormatViolation(email);
        if (emailViolation != null) {
            errorHandler(req, emailViolation, username, email, confirmPassword);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        String passwordViolation = InputValidator.getPasswordPolicyViolation(password);
        if (passwordViolation != null) {
            errorHandler(req, passwordViolation, username, email, confirmPassword);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        if (confirmPassword == null || !confirmPassword.equals(password)) {
            errorHandler(req, "Passwords do not match.", username, email, confirmPassword);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        if (req.getParameter("termsAccept") == null) {
            errorHandler(req, "You must accept the terms to continue.", username, email, confirmPassword);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }

        // Buying and selling share one account, so sign-up offers no account-type
        // choice. Any submitted `role` parameter is ignored: every registration
        // creates a BUYER, who can turn selling on from within the app.
        Role role = Role.BUYER;

        try {
            if (userDAO.checkUser(username.trim())) {
                errorHandler(req, "Username already in use!", username, email, confirmPassword);
                req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
                return;
            }
            if (userDAO.checkEmail(email.trim())) {
                errorHandler(req, "Email already in use!", username, email, confirmPassword);
                req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
                return;
            }

            String hashPassword = SecurityUtil.hashPassword(password);
            User user = new User(username, email, hashPassword, role);

            if (userDAO.insertUser(user)) {
                req.setAttribute("Insert", "Insert ran!");
            } else {
                errorHandler(req, "Registration failed. Please try again.", username, email, confirmPassword);
            }
        } catch (Throwable ex) {
            getServletContext().log("Registration database error", ex);
            errorHandler(req,
                    "Could not reach the database. Ensure PostgreSQL is running, JDBC driver is on the classpath, "
                            + "and DBUtil settings are correct.",
                    username, email, confirmPassword);
        }
        req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
    }

    private void errorHandler(HttpServletRequest req, String message, String username, String email,
                              String confirmPassword) {
        req.setAttribute("Error", message);
        stickyForm(req, username, email, confirmPassword);
    }

    private void stickyForm(HttpServletRequest req, String username, String email, String confirmPassword) {
        req.setAttribute("username", username);
        req.setAttribute("email", email);
        req.setAttribute("confirmPassword", confirmPassword);
    }
}
