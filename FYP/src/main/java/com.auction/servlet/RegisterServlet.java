package com.auction.servlet;

import java.io.IOException;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.model.Role;
import com.auction.util.InputValidator;
import at.favre.lib.crypto.bcrypt.BCrypt;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class RegisterServlet extends HttpServlet {

    private UserDAO userDAO;

    public RegisterServlet(){
        userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) // for unit testing
    {
        this.userDAO = userDAO;
    }

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
        String rolePara = req.getParameter("role");
        Role role = null;

        username = (username == null) ? null : username.trim();
        email = (email == null) ? null : email.trim().toLowerCase();
        rolePara = (rolePara == null) ? null : rolePara.trim();

        if (username == null || username.isBlank()) {
            errorHandler(req, "Username is required.", username, email, rolePara);
            return;
        }
        String emailViolation = InputValidator.getEmailFormatViolation(email);
        if (emailViolation != null) {
            errorHandler(req, emailViolation, username, email, rolePara);
            return;
        }
        String passwordViolation = InputValidator.getPasswordPolicyViolation(password);
        if (passwordViolation != null) {
            errorHandler(req, passwordViolation, username, email, rolePara);
            return;
        }
        if (rolePara == null || rolePara.isBlank()) {
            errorHandler(req, "Role is required.", username, email, rolePara);
            return;
        }
        else{
            if(rolePara.equalsIgnoreCase("seller"))
            {
                role = Role.SELLER;
            }
            else{
                role = Role.BUYER;
            }
        }

        if(userDAO.checkUser(username.trim())){
            errorHandler(req, "Username already in use!", username, email, rolePara);
            //redirect
            return;
        }
        if(userDAO.checkEmail(email.trim()))
        {
            errorHandler(req, "Email already in use!", username, email, rolePara);
            //redirect
            return;
        }

        String hashPassword = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        User user = new User(username, email, hashPassword, role);

        if(userDAO.insertUser(user))
        {
            //success
            //placeholder for unit testing
            req.setAttribute("Insert","Insert ran!");
        }
        else
        {
            //failure
        }
    }


    private void errorHandler(HttpServletRequest req, String message, String username, String email, String role)
    {
        req.setAttribute("Error", message);
        stickyForm(req, username, email, role);
    }

    private void stickyForm(HttpServletRequest req, String username, String email, String role)
    {
        req.setAttribute("username", username);
        req.setAttribute("email", email);
        req.setAttribute("role", role);
    }
}
