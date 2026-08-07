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
 *
 * <p>Legacy JSP page behind {@code AuthFilter}; the SPA reads the same data from
 * {@code /api/account/*} in {@code AccountApiServlet}. It pulls together three sources:
 * {@link UserDAO} for the profile row, {@link ProfileActivityDAO} for the transaction list,
 * the purchase and sale counters and the ratings, and {@link SecurityUtil} to decrypt the
 * stored PII and to produce the masked variants shown in the public-preview panel.</p>
 *
 * <p>The static helpers here ({@code readUserId} and {@code decryptPiiForDisplay}) are reused
 * by the other account servlets in this package, which is why they are package-private static
 * rather than instance methods.</p>
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

    /**
     * Builds the dashboard. Loads the profile, then sets both the plain values (for the owner's
     * own view) and the masked values (for the "how others see you" preview) on the request,
     * followed by the transaction list, its totals and the rating summary.
     * The optional {@code tx} parameter selects the transaction filter; anything unrecognised
     * falls back to the default inside {@code TxFilter.fromParam}.
     */
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

        // Redundant on paper, since the id came from the session in the first place. It is kept
        // as a second line of defence in case the lookup above is ever changed to accept a
        // parameter, which is the usual way an IDOR hole gets introduced.
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

        // Star count for the display widget: round the average, but never show zero stars to a
        // user who does have reviews, and never more than five.
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

    /**
     * Reads the signed-in user's id from the session, or {@code null} when nobody is signed in.
     * The attribute is written as an Integer by the JSP login path but as a Long by parts of the
     * API path, so every numeric type is accepted rather than casting and risking a
     * ClassCastException at runtime.
     */
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

    /** True when the loaded profile really belongs to the session that asked for it. */
    private static boolean isProfileOwnedBySession(HttpSession session, User profile) {
        Integer sessionUserId = readUserId(session);
        return sessionUserId != null && sessionUserId == profile.getId();
    }

    /**
     * Decrypts an AES-GCM encrypted phone or address field for display, returning {@code null}
     * when the column is empty or cannot be decrypted. Failing quietly is deliberate: a row
     * encrypted under an older key should leave one field blank on the page rather than break
     * the whole dashboard with a 500.
     */
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
