package com.auction.servlet;

import java.io.IOException;
import java.time.LocalDate;

import com.auction.dao.UserDAO;
import com.auction.model.User;
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
        String username = req.getParameter("username").trim(); //check frontend
        String email = req.getParameter("email").trim();
        String password = req.getParameter("password");
        String role = req.getParameter("role");
        LocalDate date = LocalDate.now();

        if(userDAO.checkUser(username)){
            req.setAttribute("error", "Username already in use!");
            stickyForm(req, username, email, role);
            //
            return;
        }
        if(userDAO.checkEmail(email))
        {
            req.setAttribute("error","Email already in use!");
            stickyForm(req, username, email, role);
            //
            return;
        }

        User user = new User(username, email, password, role, date);

        //insert to database
    }

    private void stickyForm(HttpServletRequest req, String username, String email, String role)
    {
        req.setAttribute("username", username);
        req.setAttribute("email", email);
        req.setAttribute("role", role);
    }
}
