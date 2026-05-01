package com.auction.servlet;

import com.auction.dao.UserDAO;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * SCRUM-10/11: profile update — validates input, re-encrypts PII with {@link SecurityUtil#encrypt(String)},
 * persists via {@link UserDAO#updateProfile(int, String, String, String, String, String)}.
 */
@WebServlet("/protected/account/update")
public class UpdateProfileServlet extends HttpServlet {

    private UserDAO userDAO;

    public UpdateProfileServlet() {
        this.userDAO = new UserDAO();
    }

    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        Integer userId = AccountManagementServlet.readUserId(session);
        if (userId == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        String username = req.getParameter("username");
        String email = req.getParameter("email");
        String phone = req.getParameter("phone");
        String address = req.getParameter("address");
        String profileImageUrl = req.getParameter("profileImageUrl");

        username = username == null ? "" : username.trim();
        email = email == null ? "" : email.trim().toLowerCase();
        phone = phone == null ? "" : phone.trim();
        address = address == null ? "" : address;
        profileImageUrl = profileImageUrl == null ? "" : profileImageUrl.trim();

        String err = InputValidator.getDisplayNameViolation(username);
        if (err != null) {
            forwardToEdit(req, resp, err, username, email, phone, address, profileImageUrl);
            return;
        }
        err = InputValidator.getEmailFormatViolation(email);
        if (err != null) {
            forwardToEdit(req, resp, err, username, email, phone, address, profileImageUrl);
            return;
        }
        err = InputValidator.getOptionalPhoneFormatViolation(phone);
        if (err != null) {
            forwardToEdit(req, resp, err, username, email, phone, address, profileImageUrl);
            return;
        }
        err = InputValidator.getOptionalAddressViolation(address);
        if (err != null) {
            forwardToEdit(req, resp, err, username, email, phone, address, profileImageUrl);
            return;
        }
        err = InputValidator.getOptionalProfileImageUrlViolation(profileImageUrl);
        if (err != null) {
            forwardToEdit(req, resp, err, username, email, phone, address, profileImageUrl);
            return;
        }

        if (userDAO.emailTakenByOtherUser(email, userId)) {
            forwardToEdit(req, resp, "That email is already in use.", username, email, phone, address, profileImageUrl);
            return;
        }
        if (userDAO.usernameTakenByOtherUser(username, userId)) {
            forwardToEdit(req, resp, "That display name is already taken.", username, email, phone, address, profileImageUrl);
            return;
        }

        String phoneEnc = phone.isEmpty() ? null : SecurityUtil.encrypt(phone);
        String addrEnc = address.isBlank() ? null : SecurityUtil.encrypt(address.trim());
        String imgUrl = profileImageUrl.isEmpty() ? null : profileImageUrl;

        if (!userDAO.updateProfile(userId, username, email, phoneEnc, addrEnc, imgUrl)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found");
            return;
        }

        session.setAttribute("sessionEmail", email);
        session.setAttribute("maskedEmail", SecurityUtil.maskEmail(email));
        session.setAttribute("maskedUsername", SecurityUtil.maskUsername(username));

        resp.sendRedirect(req.getContextPath() + "/protected/account?updated=1");
    }

    private static void forwardToEdit(HttpServletRequest req, HttpServletResponse resp, String error,
                                      String username, String email, String phone, String address, String profileImageUrl)
            throws ServletException, IOException {
        req.setAttribute("error", error);
        req.setAttribute("formUsername", username);
        req.setAttribute("formEmail", email);
        req.setAttribute("formPhone", phone);
        req.setAttribute("formAddress", address);
        req.setAttribute("formProfileImageUrl", profileImageUrl);
        req.getRequestDispatcher(EditProfileServlet.VIEW_EDIT).forward(req, resp);
    }
}
