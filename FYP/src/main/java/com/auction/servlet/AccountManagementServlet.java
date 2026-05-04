package com.auction.servlet;

import com.auction.dao.ProfileActivityDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.model.profile.RatingSummary;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Signed-in user's account dashboard. Loads the profile by {@code session.userId} only
 * (never by request parameters) so another user's row cannot be targeted.
 */
@WebServlet("/protected/account")
public class AccountManagementServlet extends HttpServlet {

    public static final String VIEW_DASHBOARD = "/WEB-INF/views/account/dashboard.jsp";

    private static final DateTimeFormatter MEMBER_SINCE_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private UserDAO userDAO;
    private ProfileActivityDAO profileActivityDAO;

    public AccountManagementServlet() {
        this.userDAO = new UserDAO();
        this.profileActivityDAO = new ProfileActivityDAO();
    }

    /** For unit tests. */
    public void setUserDAO(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** For unit tests. */
    public void setProfileActivityDAO(ProfileActivityDAO profileActivityDAO) {
        this.profileActivityDAO = profileActivityDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        Integer userId = readUserId(session);
        if (userId == null) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        User profile = userDAO.getUserById(userId);
        if (profile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found");
            return;
        }

        if (!isProfileOwnedBySession(session, profile)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
            return;
        }

        req.setAttribute("profileUsername", SecurityUtil.sanitize(profile.getUsername()));
        req.setAttribute("profileEmail", profile.getEmail());
        req.setAttribute("profileRole", profile.getRole().name());
        req.setAttribute("twoFactorEnabled", profile.isTwoFactorEnabled());

        String phonePlain = AccountManagementServlet.decryptPiiForDisplay(profile.getPhoneEncrypted());
        String addressPlain = decryptPiiForDisplay(profile.getAddressEncrypted());
        req.setAttribute("profilePhone", phonePlain);
        req.setAttribute("profileAddress", addressPlain);
        req.setAttribute("profileImageUrl", profile.getProfileImageUrl());

        if (profile.getMemberSince() != null) {
            req.setAttribute("memberSinceFormatted", MEMBER_SINCE_FMT.format(profile.getMemberSince()));
        }

        req.setAttribute("publicMaskedName", SecurityUtil.maskUsername(profile.getUsername()));
        req.setAttribute("publicMaskedEmail", SecurityUtil.maskEmail(profile.getEmail()));
        req.setAttribute("publicMaskedPhone", SecurityUtil.maskPhone(phonePlain));
        req.setAttribute("publicMaskedAddress", SecurityUtil.maskAddress(addressPlain));

        ProfileActivityDAO.TxFilter txFilter = ProfileActivityDAO.TxFilter.fromParam(req.getParameter("tx"));
        req.setAttribute("txFilter", txFilter.name().toLowerCase());
        req.setAttribute("transactions", profileActivityDAO.listTransactions(userId, txFilter));

        ProfileActivityDAO.TransactionStats txStats = profileActivityDAO.computeTransactionStats(userId);
        req.setAttribute("txPurchaseTotal", txStats.getPurchaseCount());
        req.setAttribute("txSaleTotal", txStats.getSaleCount());
        req.setAttribute("txVolumeTotal", txStats.getTotalVolume());

        RatingSummary rating = profileActivityDAO.getRatingSummary(userId);
        int ratingStarsFilled = 0;
        if (rating.getReviewCount() > 0) {
            ratingStarsFilled = (int) Math.round(rating.getAverage());
            if (ratingStarsFilled < 1) {
                ratingStarsFilled = 1;
            }
            if (ratingStarsFilled > 5) {
                ratingStarsFilled = 5;
            }
        }
        req.setAttribute("ratingStarsFilled", ratingStarsFilled);
        req.setAttribute("ratingSummary", rating);
        req.setAttribute("reviewsAboutMe", profileActivityDAO.listReviewsAboutUser(userId));

        req.getRequestDispatcher(VIEW_DASHBOARD).forward(req, resp);
    }

    static Integer readUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object raw = session.getAttribute("userId");
        if (raw instanceof Integer) {
            return (Integer) raw;
        }
        if (raw instanceof Long) {
            return ((Long) raw).intValue();
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        return null;
    }

    private static boolean isProfileOwnedBySession(HttpSession session, User profile) {
        Integer sessionUserId = readUserId(session);
        return sessionUserId != null && sessionUserId == profile.getId();
    }

    static String decryptPiiForDisplay(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            return SecurityUtil.decrypt(ciphertext);
        } catch (SecurityUtil.SecurityOperationException e) {
            return null;
        }
    }
}
