package com.auction.servlet.api;

import com.auction.dao.AuctionDAO;
import com.auction.dao.OrderDAO;
import com.auction.dao.PlatformRevenueDAO;
import com.auction.dao.RatingDAO;
import com.auction.dao.UserDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/stats — public, unauthenticated platform statistics for the landing page.
 *
 * <p>Everything here is computed from the database (or from the fee constants that
 * actually drive billing) so the marketing numbers on the home page can never drift
 * from reality. Results are cached briefly because the landing page is the highest
 * traffic route and the numbers do not need to be second-accurate.</p>
 */
@WebServlet("/api/stats")
public class PlatformStatsApiServlet extends ApiBase {

    private static final long CACHE_TTL_MS = 60_000;

    private final UserDAO userDAO = new UserDAO();
    private final AuctionDAO auctionDAO = new AuctionDAO();
    private final OrderDAO orderDAO = new OrderDAO();
    private final RatingDAO ratingDAO = new RatingDAO();

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = cached;
        if (body == null || System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) {
            try {
                body = build();
                cached = body;
                cachedAt = System.currentTimeMillis();
            } catch (RuntimeException e) {
                getServletContext().log("platform stats error", e);
                // Fail soft: the landing page should render even if stats are down.
                body = Collections.emptyMap();
            }
        }
        ok(resp, body);
    }

    private Map<String, Object> build() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalUsers", userDAO.countNonDeletedUsers());
        body.put("activeListings", auctionDAO.countListingsModerationActive());
        body.put("totalListings", auctionDAO.countListingsTotal());
        body.put("completedOrders", orderDAO.countCompletedOrders());

        // Fee schedule from the constants that billing actually uses — never hardcoded in the UI.
        Map<String, Object> fees = new LinkedHashMap<>();
        fees.put("listingFee", 0);
        fees.put("commissionPercent",
                PlatformRevenueDAO.COMMISSION_RATE.movePointRight(2).stripTrailingZeros().toPlainString());
        fees.put("featuredListingFee", PlatformRevenueDAO.FEATURED_LISTING_FEE);
        body.put("fees", fees);

        // Real buyer reviews double as landing-page testimonials.
        body.put("testimonials", ratingDAO.listTestimonials(3));
        return body;
    }
}
