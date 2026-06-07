package com.auction.servlet;

import com.auction.dao.SellerProfileDAO;
import com.auction.dao.SellerProfileDAO.AvgRating;
import com.auction.model.SellerPublicProfile;
import com.auction.model.profile.ProfileReviewRow;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Public seller profile page for buyers (SCRUM-63).
 *
 * <p>Mapped to {@code /seller/*} — seller ID is extracted from path info
 * (e.g. {@code /seller/42} → id {@code 42}). Exact paths such as
 * {@code /seller/edit-auction} are handled by other servlets.</p>
 *
 * <p><b>Auth policy (SCRUM-357):</b> No session required — buyers may view seller
 * profiles without logging in.</p>
 *
 * <p><b>Security (SCRUM-357):</b>
 * <ul>
 *   <li>{@code sellerId} parsed as {@code long}; non-numeric → 400.</li>
 *   <li>Only active Seller-role users are surfaced; others → 404 (no role leak).</li>
 *   <li>Email displayed via {@link com.auction.util.SecurityUtil#maskEmail(String)} only.</li>
 *   <li>No password, phone, address, or 2FA fields are loaded or forwarded.</li>
 * </ul>
 * </p>
 */
@WebServlet("/seller/*")
public class SellerProfileServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(SellerProfileServlet.class.getName());

    public static final int DEFAULT_REVIEW_PAGE_SIZE = 10;
    public static final int MAX_REVIEW_PAGE_SIZE = 50;

    private SellerProfileDAO sellerProfileDAO;

    public SellerProfileServlet() {
        this.sellerProfileDAO = new SellerProfileDAO();
    }

    public SellerProfileServlet(SellerProfileDAO sellerProfileDAO) {
        this.sellerProfileDAO = sellerProfileDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        long sellerId = parseSellerId(req, resp);
        if (sellerId < 0) return;

        SellerPublicProfile profile;
        AvgRating avgRating;
        List<ProfileReviewRow> reviews;
        int reviewPage = parseReviewPage(req);
        int reviewPageSize = parseReviewPageSize(req);
        int totalReviews;

        try {
            profile = sellerProfileDAO.getPublicProfile(sellerId);
            if (profile == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Seller not found.");
                return;
            }

            avgRating = sellerProfileDAO.getAvgRating(sellerId);
            totalReviews = avgRating.getCount();
            reviews = sellerProfileDAO.getReviews(sellerId, reviewPage, reviewPageSize);
        } catch (RuntimeException e) {
            LOGGER.severe("SellerProfileServlet DB error [sellerId=" + sellerId + "]: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        int totalReviewPages = totalReviews == 0 ? 1
                : (int) Math.ceil((double) totalReviews / reviewPageSize);
        if (reviewPage > totalReviewPages) {
            reviewPage = totalReviewPages;
            reviews = sellerProfileDAO.getReviews(sellerId, reviewPage, reviewPageSize);
        }

        LOGGER.fine(String.format("Seller profile [id=%d, reviews=%d, avg=%.1f, listings=%d].",
                sellerId, totalReviews, avgRating.getAverage(), profile.getActiveListingCount()));

        req.setAttribute("profile", profile);
        req.setAttribute("avgRating", avgRating.getAverage());
        req.setAttribute("reviewCount", totalReviews);
        req.setAttribute("reviews", reviews);
        req.setAttribute("reviewPage", reviewPage);
        req.setAttribute("reviewTotalPages", totalReviewPages);
        req.setAttribute("reviewPageSize", reviewPageSize);
        req.setAttribute("reviewsEmpty", reviews.isEmpty());

        req.getRequestDispatcher("/WEB-INF/views/seller-profile.jsp").forward(req, resp);
    }

    // =========================================================================
    // Parsing helpers (public for unit tests)
    // =========================================================================

    /**
     * Parses seller id from {@code pathInfo} ({@code /seller/42} → {@code 42}).
     *
     * @return seller id, or {@code -1} after sending 400
     */
    public static long parseSellerId(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.length() <= 1) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Seller ID is required.");
            return -1;
        }
        try {
            return Long.parseLong(pathInfo.substring(1).trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid seller ID.");
            return -1;
        }
    }

    public static int parseReviewPage(HttpServletRequest req) {
        try {
            String p = req.getParameter("page");
            if (p != null && !p.isBlank()) return Math.max(1, Integer.parseInt(p.trim()));
        } catch (NumberFormatException ignored) { }
        return 1;
    }

    public static int parseReviewPageSize(HttpServletRequest req) {
        try {
            String s = req.getParameter("size");
            if (s != null && !s.isBlank()) {
                return Math.min(MAX_REVIEW_PAGE_SIZE, Math.max(1, Integer.parseInt(s.trim())));
            }
        } catch (NumberFormatException ignored) { }
        return DEFAULT_REVIEW_PAGE_SIZE;
    }
}
