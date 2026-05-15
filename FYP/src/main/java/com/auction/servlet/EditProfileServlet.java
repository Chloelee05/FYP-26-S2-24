package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebServlet("/protected/account/edit")
public class EditProfileServlet extends HttpServlet {

    public static final String VIEW_EDIT = "/WEB-INF/views/account/edit.jsp";

    private UserDAO userDAO;

    public EditProfileServlet() {
        this.userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (req.getAttribute("formUsername") != null) {
            req.getRequestDispatcher(VIEW_EDIT).forward(req, resp);
            return;
        }

        HttpSession session = req.getSession(false);
        Integer userId = AccountManagementServlet.readUserId(session);
        if (userId == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        User profile = userDAO.getUserById(userId);
        if (profile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found");
            return;
        }

        populateFormFromUser(req, profile);
        req.getRequestDispatcher(VIEW_EDIT).forward(req, resp);
    }

    static void populateFormFromUser(HttpServletRequest req, User profile) {
        req.setAttribute("formUsername", profile.getUsername());
        req.setAttribute("formEmail", profile.getEmail());
        req.setAttribute("formPhone", AccountManagementServlet.decryptPiiForDisplay(profile.getPhoneEncrypted()));
        req.setAttribute("formAddress", AccountManagementServlet.decryptPiiForDisplay(profile.getAddressEncrypted()));
        String img = profile.getProfileImageUrl();
        req.setAttribute("formProfileImageUrl", img != null ? img : "");
    }
}
