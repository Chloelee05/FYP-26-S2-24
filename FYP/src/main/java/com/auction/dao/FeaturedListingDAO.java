package com.auction.dao;

import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;
import com.auction.util.DutchClock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Featured / promoted listings, part of the business model. There is no billing UI; the fee is
 * only booked into {@code platform_revenue} by {@link PlatformRevenueDAO}.
 *
 * <p>Updates the {@code is_featured} and {@code featured_until} columns on {@code auction}, and
 * reads a joined view of {@code auction}, {@code auction_details}, {@code users}, {@code bids} and
 * {@code auction_images} to build the promoted carousel. Called by the admin/seller featuring
 * endpoint and by the landing page API. The list query carries the blind-auction price guard.</p>
 */
public class FeaturedListingDAO {

    /**
     * Marks a listing as promoted for a number of days.
     *
     * @param days promotion length; a non-positive value falls back to 7 days
     */
    public boolean featureAuction(long auctionId, int days) {
        int d = days <= 0 ? 7 : days;
        // The expiry is computed by the database rather than in Java so it uses the server's clock,
        // which is the same clock the listActiveFeatured filter compares against.
        String sql =
            "UPDATE auction SET is_featured = TRUE, "
          + "featured_until = now() + (? || ' days')::interval "
          + "WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, d);
            ps.setLong(2, auctionId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Removes promotion immediately, ahead of the scheduled expiry. */
    public boolean unfeatureAuction(long auctionId) {
        String sql = "UPDATE auction SET is_featured = FALSE, featured_until = NULL WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The promoted carousel: currently featured, still-open, publicly visible listings.
     *
     * <p>Each row is one card. The query assembles the auction row, its details, the seller's
     * username, one thumbnail and a price, then Java applies the Dutch clock on top.</p>
     *
     * @param limit maximum number of cards to return
     */
    public List<SearchResultItem> listActiveFeatured(int limit) {
        String sql =
            "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: these listings are all still
          // open, so their leading sealed bid must not reach the client. auction_type 3 is BLIND;
          // for every other type the price shown is the highest bid so far, or the starting price
          // when nobody has bid yet, which is what the COALESCE around the MAX subquery handles.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + "  d.starting_price, d.dutch_floor_price, a.date_created, "
          + "  a.date_end, u.username, "
          // Correlated subquery picks the first uploaded image as the card thumbnail. Ordering by
          // id keeps the choice stable between page loads.
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.is_featured = TRUE "
          // A promotion can be open-ended (null expiry) or time-boxed; expired ones drop out here
          // rather than needing a scheduled job to clear the flag.
          + "  AND (a.featured_until IS NULL OR a.featured_until > now()) "
          // status_id 1 is an active auction. The moderation and end-time checks keep suspended or
          // already-closed listings out of a public surface.
          + "  AND a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() "
          + "ORDER BY a.featured_until DESC NULLS LAST, a.date_end ASC "
          + "LIMIT ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SearchResultItem> out = new ArrayList<>();
                while (rs.next()) {
                    Timestamp end = rs.getTimestamp("date_end");
                    Timestamp start = rs.getTimestamp("date_created");
                    Instant endInstant = end != null ? end.toInstant() : null;
                    int typeId = rs.getInt("auction_type");
                    // A Dutch listing has no bids until the single acceptance that closes it, so
                    // the SQL price is only its high start. DutchClock is the one shared place the
                    // declining price is computed, which stops this carousel quoting a different
                    // figure from the browse grid or the detail page.
                    BigDecimal price = DutchClock.listedPrice(typeId,
                            rs.getBigDecimal("current_price"),
                            rs.getBigDecimal("starting_price"), rs.getBigDecimal("dutch_floor_price"),
                            start != null ? start.toInstant() : null,
                            endInstant, Instant.now());
                    out.add(new SearchResultItem(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            rs.getString("category"),
                            price,
                            endInstant,
                            rs.getString("username"),
                            rs.getString("thumb"),
                            typeId));
                }
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Owner of a listing, used by the API to check that the caller may feature it.
     *
     * @return the seller's user id, or -1 when the auction does not exist
     */
    public int sellerIdForAuction(long auctionId) {
        String sql = "SELECT seller_id FROM auction WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("seller_id") : -1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
