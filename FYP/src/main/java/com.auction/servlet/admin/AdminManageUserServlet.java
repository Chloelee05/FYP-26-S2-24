package com.auction.servlet.admin;

import java.io.IOException;
import java.util.Objects;

import com.auction.dao.UserDAO;

import com.auction.model.User;
import com.auction.model.Role;
import com.auction.model.Status;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class AdminManageUserServlet extends HttpServlet{

    private UserDAO userDAO;

    public AdminManageUserServlet(){
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
        String action = req.getParameter("action");
        String userid = req.getParameter("userid");
        if(action == null || userid  == null || userid.isBlank())
        {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        int targetUserID = Integer.parseInt(userid.trim());
        switch (action) {
            case ("Suspend"):
                if(userDAO.updateStatus(targetUserID, Status.SUSPENDED.getId()))
                {
                    req.setAttribute("Success","Account successfully suspended!");
                }
                break;
            case("Active"):
                if(userDAO.updateStatus(targetUserID, Status.ACTIVE.getId()))
                {
                    req.setAttribute("Success","Account successfully unsuspended!");
                }
                break;
            default:
                //add more actions as needed
                break;
        }
    }
}
