package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.AuctionType;
import com.auction.model.SellerPublicProfile;
import com.auction.model.profile.ProfileReviewRow;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the public seller profile page (SCRUM-63).
 *
 * <p>Only users with the Seller role and Active status are returned by
 * {@link #getPublicProfile(long)}. Buyer, admin and suspended accounts yield {@code null}
 * so the servlet can respond with 404 without leaking role information.</p>
 *
 * <p>Reviewer names in {@link #getReviews(long, int, int)} are masked; raw emails and
 * encrypted PII columns are never selected.</p>
 *
 * <p>Reads {@code users}, {@code user_reviews}, {@code auction}, {@code auction_details},
 * {@code bids}, {@code auction_images}, {@code watchlist} and {@code orders}. Called by the seller
 * profile API servlet, which serves guests as well as signed-in users. Because the page is
 * public, the listings query carries the blind-auction price guard and the seller's email is
 * masked before it leaves the DAO.</p>
 */
public class SellerProfileDAO {

    /** Seller role_id in {@code roles} table (matches {@link com.auction.model.Role#SELLER}). */
    static final int SELLER_ROLE_ID = 3;

    /** Active status_id in {@code user_status} table. */
    static final int ACTIVE_STATUS_ID = 1;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Average rating and review count for a seller, returned together so the UI can show "4.6 (23)". */
    public static final class AvgRating {
        private final double average;
        private final int count;

        public AvgRating(double average, int count) {
            this.average = average;
            this.count = count;
        }

        public double getAverage() { return average; }
        public int getCount() { return count; }
    }

    /**
     * Loads the public seller profile, or {@code null} when the user is not an active seller.
     *
     * @param sellerId parsed seller id from URL path
     */
    public SellerPublicProfile getPublicProfile(long sellerId) {
        String sql =
                "SELECT u.id, u.username, u.email, u.date_created, u.profile_image_url, "
                // Live listing count as a scalar subquery, so the profile header can show it
                // without a second round trip or a GROUP BY over the user row.
                + "       (SELECT COUNT(*)::int FROM auction a "
                + "        WHERE a.seller_id = u.id "
                + "          AND a.moderation_state = 'active' "
                + "          AND a.date_end > CURRENT_TIMESTAMP) AS active_listings "
                + "FROM users u "
                + "WHERE u.id = ? "
                // Selling is a capability now; the role check keeps pre-merge
                // seller accounts resolvable on an un-migrated database.
                + "  AND (u.can_sell = TRUE OR u.role_id = ?) "
                + "  AND u.status_id = ?";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            ps.setInt(2, SELLER_ROLE_ID);
            ps.setInt(3, ACTIVE_STATUS_ID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Timestamp created = rs.getTimestamp("date_created");
                // The profile is public, so the seller's address is masked (PDPA). Only enough
                // survives to let a buyer recognise their own contact, e.g. "j***@gmail.com".
                String rawEmail = rs.getString("email");
                return new SellerPublicProfile(
                        rs.getLong("id"),
                        rs.getString("username"),
                        SecurityUtil.maskEmail(rawEmail),
                        created != null ? created.toInstant() : null,
                        rs.getString("profile_image_url"),
                        rs.getInt("active_listings"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the average star rating and total review count for a seller.
     *
     * @param sellerId seller user id
     */
    public AvgRating getAvgRating(long sellerId) {
        // AVG over zero rows is NULL; COALESCE makes an unrated seller read as 0.0 rather than
        // forcing the caller to distinguish null from zero.
        String sql =
                "SELECT COALESCE(AVG(rating), 0) AS avg_rating, COUNT(*)::int AS review_count "
                + "FROM user_reviews WHERE reviewee_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double avg = rs.getDouble("avg_rating");
                    int count = rs.getInt("review_count");
                    // Round to one decimal only when there is something to round. Rounding is done
                    // here rather than in SQL so the star widget always gets a value like 4.6.
                    if (count > 0) {
                        avg = BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).doubleValue();
                    }
                    return new AvgRating(avg, count);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new AvgRating(0, 0);
    }

    /**
     * Returns a paginated page of reviews received by the seller, newest first.
     * Reviewer usernames are masked; comments are stored sanitized at insert time.
     *
     * @param sellerId seller user id
     * @param page     1-based page number
     * @param pageSize rows per page
     */
    public List<ProfileReviewRow> getReviews(long sellerId, int page, int pageSize) {
        String sql =
                "SELECT r.rating, r.comment, r.created_at, u.username, d.title AS item_title "
                + "FROM user_reviews r "
                + "JOIN users u ON u.id = r.reviewer_user_id "
                // LEFT JOIN so a review still shows even if its auction row has since been removed;
                // item_title simply comes back null in that case.
                + "LEFT JOIN auction_details d ON d.id = r.auction_id "
                + "WHERE r.reviewee_user_id = ? "
                + "ORDER BY r.created_at DESC "
                + "LIMIT ? OFFSET ?";

        List<ProfileReviewRow> list = new ArrayList<>();
        // Page numbers are 1-based in the API, so page 1 must map to offset 0.
        int offset = pageSize * (page - 1);
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapReviewRow(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /**
     * One of the seller's live listings, shaped like the search results so the
     * public profile can render it with the same auction card as everywhere else.
     */
    public static final class PublicListing {
        private final long auctionId;
        private final String title;
        private final String category;
        private final BigDecimal currentPrice;
        private final Instant endDate;
        private final String thumbnailUrl;
        private final int watchCount;

        PublicListing(long auctionId, String title, String category, BigDecimal currentPrice,
                      Instant endDate, String thumbnailUrl, int watchCount) {
            this.auctionId = auctionId;
            this.title = title;
            this.category = category;
            this.currentPrice = currentPrice;
            this.endDate = endDate;
            this.thumbnailUrl = thumbnailUrl;
            this.watchCount = watchCount;
        }

        public long getAuctionId() { return auctionId; }
        public String getTitle() { return title; }
        public String getCategory() { return category; }
        public BigDecimal getCurrentPrice() { return currentPrice; }
        public Instant getEndDate() { return endDate; }
        public String getThumbnailUrl() { return thumbnailUrl; }
        public int getWatchCount() { return watchCount; }
    }

    /**
     * Live listings for the public profile: moderation-visible auctions that have
     * not ended yet, soonest closing first, so buyers can browse what this seller has
     * on sale right now.
     *
     * <p>Each row carries a price, a thumbnail and a watcher count, all pulled as scalar
     * subqueries against {@code bids}, {@code auction_images} and {@code watchlist} so the
     * auction row is never duplicated by a join.</p>
     *
     * @param sellerId seller user id
     * @param limit    maximum rows (caller clamps)
     */
    public List<PublicListing> getActiveListings(long sellerId, int limit) {
        String sql =
                "SELECT a.auction_id, d.title, d.category, a.date_end, "
                // Blind auctions resolve to the entry price. This profile is public, so an
                // unauthenticated visitor can open any seller's page, and the WHERE clause
                // below restricts it to listings that are still taking bids, so every sealed
                // row here is one whose leading bid must not leave the server. The same guard
                // appears in SearchDAO, RecommendationDAO, FeaturedListingDAO and WatchlistDAO.
                + "CASE WHEN a.auction_type = " + AuctionType.BLIND.getId()
                + "       AND a.status_id = " + AuctionStatus.ACTIVE.getId()
                + "     THEN d.starting_price "
                + "     ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), "
                + "                   d.starting_price) END AS current_price, "
                + "(SELECT ai.image_url FROM auction_images ai "
                + " WHERE ai.auction_id = a.auction_id ORDER BY ai.id LIMIT 1) AS thumbnail_url, "
                + "(SELECT COUNT(*)::int FROM watchlist w WHERE w.auction_id = a.auction_id) AS watch_count "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "WHERE a.seller_id = ? "
                + "  AND a.moderation_state = 'active' "
                + "  AND a.date_end > CURRENT_TIMESTAMP "
                + "ORDER BY a.date_end ASC "
                + "LIMIT ?";

        List<PublicListing> list = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp end = rs.getTimestamp("date_end");
                    BigDecimal price = rs.getBigDecimal("current_price");
                    list.add(new PublicListing(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            rs.getString("category"),
                            price == null ? BigDecimal.ZERO : price,
                            end == null ? null : end.toInstant(),
                            rs.getString("thumbnail_url"),
                            rs.getInt("watch_count")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /** Completed sales (orders marked COMPLETED). */
    public int countCompletedTransactions(long sellerId) {
        String sql = "SELECT COUNT(*)::int FROM orders WHERE seller_id = ? AND status = 'COMPLETED'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /** Total review count for pagination (same filter as {@link #getReviews}). */
    public int countReviews(long sellerId) {
        String sql = "SELECT COUNT(*)::int FROM user_reviews WHERE reviewee_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /**
     * Builds one review row. The reviewer's username is masked here, so no public read path out of
     * this DAO can return a full identity.
     */
    private static ProfileReviewRow mapReviewRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDate d = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
        String comment = rs.getString("comment");
        if (comment == null) comment = "";
        return new ProfileReviewRow(
                SecurityUtil.maskUsername(rs.getString("username")),
                rs.getInt("rating"),
                comment,
                d,
                rs.getString("item_title"));
    }
}
