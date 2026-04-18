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

public class RegisterServlet extends HttpServlet {

    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String username = req.getParameter("username");
        String email = req.getParameter("email");
        String password = req.getParameter("password");
        String confirmPassword = req.getParameter("confirmPassword");
        String fullName = req.getParameter("fullName");
        String role = req.getParameter("role");

        // Validation
        if (username == null || username.trim().isEmpty() ||
            email == null || email.trim().isEmpty() ||
            password == null || password.isEmpty() ||
            fullName == null || fullName.trim().isEmpty()) {
            req.setAttribute("error", "Please fill in all required fields.");
            preserveFormData(req, username, email, fullName, role);
            req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
            return;
        }

        if (!password.equals(confirmPassword)) {
            req.setAttribute("error", "Passwords do not match.");
            preserveFormData(req, username, email, fullName, role);
            req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
            return;
        }

        if (password.length() < 6) {
            req.setAttribute("error", "Password must be at least 6 characters.");
            preserveFormData(req, username, email, fullName, role);
            req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
            return;
        }

        if (userDAO.findByUsername(username.trim()) != null) {
            req.setAttribute("error", "Username already taken.");
            preserveFormData(req, username, email, fullName, role);
            req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
            return;
        }

        if (userDAO.findByEmail(email.trim()) != null) {
            req.setAttribute("error", "Email already registered.");
            preserveFormData(req, username, email, fullName, role);
            req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
            return;
        }

        if (role == null || (!role.equals("BUYER") && !role.equals("SELLER"))) {
            role = "BUYER";
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setEmail(email.trim());
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setFullName(fullName.trim());
        user.setRole(role);
        user.setStatus("ACTIVE");

        if (userDAO.insert(user)) {
            HttpSession session = req.getSession(true);
            session.setAttribute("user", user);
            resp.sendRedirect(req.getContextPath() + "/");
        } else {
            req.setAttribute("error", "Registration failed. Please try again.");
            preserveFormData(req, username, email, fullName, role);
            req.getRequestDispatcher("/auth/register.jsp").forward(req, resp);
        }
    }

    private void preserveFormData(HttpServletRequest req, String username, String email, String fullName, String role) {
        req.setAttribute("username", username);
        req.setAttribute("email", email);
        req.setAttribute("fullName", fullName);
        req.setAttribute("role", role);
    }
}
