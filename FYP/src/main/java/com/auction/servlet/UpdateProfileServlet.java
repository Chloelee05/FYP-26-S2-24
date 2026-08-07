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
 *
 * <p>Save handler for the form rendered by {@link EditProfileServlet}. On any validation
 * problem it forwards back to that form with the rejected values, so the user never loses
 * their input. Legacy JSP flow behind {@code AuthFilter}; the SPA uses {@code /api/account/*}
 * in {@code AccountApiServlet}.</p>
 *
 * <p>The target row is always the session's own user id. Username and email uniqueness are
 * checked with the "excluding self" DAO variants, otherwise saving the form unchanged would
 * report your own email as taken.</p>
 */
@WebServlet("/protected/account/update")
public class UpdateProfileServlet extends HttpServlet {

    private UserDAO userDAO;

    public UpdateProfileServlet() {
        this.userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /**
     * Validates and saves the profile. Accepts {@code username}, {@code email}, and the optional
     * {@code phone}, {@code address} and {@code profileImageUrl}. Phone and address are encrypted
     * before they reach the database, and an empty optional field is stored as SQL NULL rather
     * than as an encrypted empty string, so "no value" stays distinguishable from "blank value".
     */
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

        // The session caches the email and the masked display strings, so they have to be
        // refreshed here or the navbar would keep showing the old name until the next sign-in.
        session.setAttribute("sessionEmail", email);
        session.setAttribute("maskedEmail", SecurityUtil.maskEmail(email));
        session.setAttribute("maskedUsername", SecurityUtil.maskUsername(username));

        resp.sendRedirect(req.getContextPath() + "/protected/account?updated=1");
    }

    /**
     * Sends the user back to the edit form with an error banner and the values they submitted,
     * including the ones that were fine, so nothing has to be retyped.
     */
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
