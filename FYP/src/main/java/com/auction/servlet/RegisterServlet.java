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
        String rolePara = req.getParameter("role");
        Role role = null;

        username = (username == null) ? null : username.trim();
        email = (email == null) ? null : email.trim().toLowerCase();
        rolePara = (rolePara == null) ? null : rolePara.trim();

        if (username == null || username.isBlank()) {
            errorHandler(req, "Username is required.", username, email, rolePara);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        String emailViolation = InputValidator.getEmailFormatViolation(email);
        if (emailViolation != null) {
            errorHandler(req, emailViolation, username, email, rolePara);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        String passwordViolation = InputValidator.getPasswordPolicyViolation(password);
        if (passwordViolation != null) {
            errorHandler(req, passwordViolation, username, email, rolePara);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        if (rolePara == null || rolePara.isBlank()) {
            errorHandler(req, "Role is required.", username, email, rolePara);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        if (rolePara.equalsIgnoreCase("seller")) {
            role = Role.SELLER;
        } else {
            role = Role.BUYER;
        }

        if (userDAO.checkUser(username.trim())) {
            errorHandler(req, "Username already in use!", username, email, rolePara);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }
        if (userDAO.checkEmail(email.trim())) {
            errorHandler(req, "Email already in use!", username, email, rolePara);
            req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
            return;
        }

        String hashPassword = SecurityUtil.hashPassword(password);
        User user = new User(username, email, hashPassword, role);

        if (userDAO.insertUser(user)) {
            req.setAttribute("Insert", "Insert ran!");
        } else {
            errorHandler(req, "Registration failed. Please try again.", username, email, rolePara);
        }
        req.getRequestDispatcher(VIEW_REGISTER).forward(req, resp);
    }

    private void errorHandler(HttpServletRequest req, String message, String username, String email, String role) {
        req.setAttribute("Error", message);
        stickyForm(req, username, email, role);
    }

    private void stickyForm(HttpServletRequest req, String username, String email, String role) {
        req.setAttribute("username", username);
        req.setAttribute("email", email);
        req.setAttribute("role", role);
    }
}
