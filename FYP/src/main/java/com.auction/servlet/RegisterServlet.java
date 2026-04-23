package com.auction.servlet;

import java.io.IOException;
import java.time.LocalDate;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.InputValidator;
import at.favre.lib.crypto.bcrypt.BCrypt;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class RegisterServlet extends HttpServlet {

    UserDAO userDAO = new UserDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //
        String username = req.getParameter("username"); //check frontend
        String email = req.getParameter("email");
        String password = req.getParameter("password");
        String role = req.getParameter("role");

        if (username == null || username.isBlank()) {
            req.setAttribute("Error", "Username is required.");
            stickyForm(req, username, email, role);
            return;
        }
        String emailViolation = InputValidator.getEmailFormatViolation(email);
        if (emailViolation != null) {
            req.setAttribute("Error", emailViolation);
            stickyForm(req, username, email, role);
            return;
        }
        String passwordViolation = InputValidator.getPasswordPolicyViolation(password);
        if (passwordViolation != null) {
            req.setAttribute("Error", passwordViolation);
            stickyForm(req, username, email, role);
            return;
        }
        if (role == null || role.isBlank()) {
            req.setAttribute("Error", "Role is required.");
            stickyForm(req, username, email, role);
            return;
        }

        if(userDAO.checkUser(username.trim())){
            req.setAttribute("Error", "Username already in use!");
            stickyForm(req, username, email, role);
            //redirect
            return;
        }
        if(userDAO.checkEmail(email.trim()))
        {
            req.setAttribute("Error","Email already in use!");
            stickyForm(req, username, email, role);
            //redirect
            return;
        }

        String hashPassword = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        User user = new User(username, email, hashPassword, role);

        if(userDAO.insertUser(user))
        {
            //success
        }
        else
        {
            //failure
        }
    }

    private void stickyForm(HttpServletRequest req, String username, String email, String role)
    {
        req.setAttribute("username", username);
        req.setAttribute("email", email);
        req.setAttribute("role", role);
    }
}
