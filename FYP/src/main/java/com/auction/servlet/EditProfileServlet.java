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

/**
 * Renders the profile edit form for the signed-in user. Read-only: the actual save is handled
 * by {@link UpdateProfileServlet} at {@code /protected/account/update}, which forwards back to
 * this servlet's view when validation fails.
 *
 * <p>Legacy JSP page behind {@code AuthFilter}. The SPA edits a profile through
 * {@code /api/account/*} in {@code AccountApiServlet}.</p>
 *
 * <p>Phone and address arrive from the database AES-GCM encrypted and are decrypted here for
 * the form fields, which is the one place the plaintext appears outside the account owner's
 * own dashboard.</p>
 */
@WebServlet("/protected/account/edit")
public class EditProfileServlet extends HttpServlet {

    public static final String VIEW_EDIT = "/WEB-INF/views/account/edit.jsp";

    private UserDAO userDAO;

    public EditProfileServlet() {
        this.userDAO = new UserDAO();
    }

    /** Injection point for a stub DAO in unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /**
     * Shows the edit form, filled from the database on a normal visit.
     * The early exit handles a redisplay after a failed save: the update servlet has already
     * put the rejected values on the request, and reloading from the database here would throw
     * away what the user typed.
     */
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

    /** Copies the stored profile onto the request as the {@code form*} attributes the JSP reads. */
    static void populateFormFromUser(HttpServletRequest req, User profile) {
        req.setAttribute("formUsername", profile.getUsername());
        req.setAttribute("formEmail", profile.getEmail());
        req.setAttribute("formPhone", AccountManagementServlet.decryptPiiForDisplay(profile.getPhoneEncrypted()));
        req.setAttribute("formAddress", AccountManagementServlet.decryptPiiForDisplay(profile.getAddressEncrypted()));
        String img = profile.getProfileImageUrl();
        req.setAttribute("formProfileImageUrl", img != null ? img : "");
    }
}
