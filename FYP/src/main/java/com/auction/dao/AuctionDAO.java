package com.auction.dao;

import com.auction.model.*;
import com.auction.model.admin.AdminListingRow;
import com.auction.model.admin.TopStatistics;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Auction persistence for admin moderation and dashboard metrics, plus the multi-table insert
 * that creates a listing.
 *
 * <p>Writes {@code auction}, {@code auction_details}, {@code auction_images} and
 * {@code auction_tag_info}; reads those plus {@code users}, {@code auction_status} and
 * {@code bids}. Called by the admin moderation and dashboard APIs, and by the listing creation
 * flow. Creating an auction spans four tables, so {@link #createAuction} runs as one transaction:
 * a listing must never exist without its details row.</p>
 */
public class AuctionDAO {

    private static final ZoneId ADMIN_ZONE = ZoneId.systemDefault();

    /**
     * The admin moderation queue: every listing with its seller, category, leading bid, report
     * count and current states. Ordered by report count so the most-complained-about listings sit
     * at the top of the page. Unlike the buyer-facing queries this deliberately has no visibility
     * filter, because moderators need to see removed and flagged rows too.
     */
    public List<AdminListingRow> listListingsForModeration() {
        try (Connection conn = DBUtil.connectDB()) {
            // The leading bid comes from a correlated subquery coalesced to 0, so a listing with no
            // bids still produces one row rather than being dropped or showing null.
            String sql = "SELECT a.auction_id, d.title, a.date_created, u.username, "
                    + "d.category, "
                    + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), 0) AS current_bid, "
                    + "a.report_count, a.moderation_state, a.is_featured, s.status AS auction_status "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "JOIN users u ON u.id = a.seller_id "
                    + "JOIN auction_status s ON s.id = a.status_id "
                    + "ORDER BY a.report_count DESC, a.auction_id DESC";
            List<AdminListingRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate listed = rs.getTimestamp("date_created").toInstant()
                            .atZone(ADMIN_ZONE).toLocalDate();
                    AdminListingRow row = new AdminListingRow(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            listed,
                            rs.getString("username"),
                            rs.getString("category"),
                            rs.getBigDecimal("current_bid"),
                            rs.getInt("report_count"),
                            rs.getString("moderation_state"));
                    row.setFeatured(rs.getBoolean("is_featured"));
                    row.setAuctionStatus(rs.getString("auction_status"));
                    rows.add(row);
                }
            }
            return rows;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets a listing's moderation state directly. Unlike {@link #updateAuctionState} this does no
     * whitelist check, so callers must pass a value the CHECK constraint accepts.
     */
    public boolean updateModerationState(long auctionId, String state) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE auction SET moderation_state = ? WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state);
                ps.setLong(2, auctionId);
                return ps.executeUpdate() == 1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Bumps a listing's report tally, which is what orders the moderation queue. */
    public boolean incrementReports(long auctionId) {
        try (Connection conn = DBUtil.connectDB()) {
            // Incremented in SQL rather than read-modify-write in Java, so two people reporting the
            // same listing at once both count.
            String sql = "UPDATE auction SET report_count = report_count + 1 WHERE auction_id = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setLong(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Every listing ever created, including ended and removed ones. Admin dashboard tile. */
    public int countListingsTotal() {
        return countQuery("SELECT COUNT(*) FROM auction");
    }

    /** Listings a buyer could bid on right now. Feeds the public landing page counter. */
    public int countListingsModerationActive() {
        // Live / bid-able listings only. Matches the search and trending filters so the
        // landing-page hero metric never over-counts ended or pending lots. status_id 1 is ACTIVE.
        return countQuery(
                "SELECT COUNT(*) FROM auction "
                        + "WHERE moderation_state = 'active' "
                        + "AND status_id = 1 "
                        + "AND date_end > now()");
    }

    /** Listings awaiting a moderator decision. */
    public int countListingsFlagged() {
        return countQuery("SELECT COUNT(*) FROM auction WHERE moderation_state = 'flagged'");
    }

    /**
     * Sum of winning_bid over completed listing rows, in whole dollars.
     *
     * <p>Reads the aggregate as a {@link BigDecimal} and rounds once, at the end.
     * {@code winning_bid} is NUMERIC(12,2) as of migration_seller_maintain_listing.sql, and
     * {@code getLong} on a numeric aggregate truncates toward zero, which threw away up to a
     * dollar of the platform's total revenue rather than just the cents it looks like.
     * The whole-dollar return type is kept because the admin dashboard, the generated PDF
     * report and their tests all consume it as a {@code long}; rounding the total once is the
     * closest correct figure that shape can carry.</p>
     */
    public long sumWinningBidDollars() {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT COALESCE(SUM(winning_bid), 0) FROM auction_details WHERE winning_bid IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal total = rs.getBigDecimal(1);
                    return total == null ? 0L
                            : total.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0L;
    }

    /** The most recently created flagged listings, for the moderation activity feed. */
    public List<FlaggedTitleEvent> recentFlaggedListings(int limit) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT d.title, a.date_created "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.moderation_state = 'flagged' "
                    + "ORDER BY a.date_created DESC "
                    + "LIMIT ?";
            List<FlaggedTitleEvent> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Instant at = rs.getTimestamp("date_created").toInstant();
                        out.add(new FlaggedTitleEvent(rs.getString("title"), at));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs a parameterless single-value COUNT. Shared by the dashboard tiles above. */
    private static int countQuery(String sql) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /** A flagged listing's title and creation time, the pair the activity feed renders. */
    public static final class FlaggedTitleEvent {
        private final String title;
        private final Instant at;

        public FlaggedTitleEvent(String title, Instant at) {
            this.title = title;
            this.at = at;
        }

        public String getTitle() {
            return title;
        }

        public Instant getAt() {
            return at;
        }
    }

    /**
     * Creates a listing across four tables in one transaction: the auction row, its details, its
     * images and its tags. Any failure rolls the lot back, so a half-built listing with no title
     * or no price cannot reach the browse grid.
     *
     * @param imageFilenames uploaded image paths in display order; may be empty
     * @return the generated auction id
     */
    public long createAuction(Auction auction, List<String> imageFilenames) throws Exception {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try {
                long auctionId = insertAuction(conn, auction);
                insertAuctionDetails(conn, auctionId, auction);
                insertAuctionImages(conn, auctionId, imageFilenames);
                insertAuctionTags(conn, auctionId, auction.getAuctionTagsList());
                conn.commit();
                return auctionId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** The parent row: who is selling, the window, the auction type, and the initial status. */
    private long insertAuction(Connection conn, Auction auction) throws Exception {
        String sql = "INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type) VALUES(?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            // Use PENDING when the start date is in the future; ACTIVE otherwise. A scheduled
            // listing must not be bid-able before it opens, and the buyer-facing queries all filter
            // on status_id = 1, so PENDING keeps it out of search until its start time.
            boolean scheduled = auction.getStart_date() != null
                    && auction.getStart_date().isAfter(java.time.Instant.now());
            stmt.setInt(1, scheduled ? AuctionStatus.PENDING.getId() : AuctionStatus.ACTIVE.getId());
            stmt.setInt(2, auction.getSeller_id());
            stmt.setTimestamp(3, Timestamp.from(auction.getStart_date()));
            stmt.setTimestamp(4, Timestamp.from(auction.getEnd_date()));
            stmt.setInt(5, auction.getAuctionType().getId());
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
                throw new Exception("Failed to retrieve generated auction ID");
            }
        }
    }

    /**
     * The details row, keyed by the same id as the auction row (a one-to-one extension table).
     * The optional money columns are written as SQL NULL rather than zero when absent, because
     * each belongs to a different auction type: {@code dutch_floor_price} to a Dutch clock,
     * {@code buy_it_now_price} to an instant purchase, {@code cost_price} to the seller's own
     * margin reporting. A zero would be a real price and would change behaviour.
     */
    private void insertAuctionDetails(Connection conn, long auctionId, Auction auction) throws Exception {
        String sql = "INSERT INTO auction_details "
                   + "(id, title, description, category, item_condition_id, starting_price, max_price, "
                   + " quantity, cost_price, dutch_floor_price, buy_it_now_price, listing_kind) "
                   + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, auctionId);
            stmt.setString(2, auction.getAuction_name());
            stmt.setString(3, auction.getAuction_details());
            stmt.setString(4, auction.getCategory() != null ? auction.getCategory() : "");
            stmt.setInt(5, auction.getItemCondition().getId());
            stmt.setBigDecimal(6, BigDecimal.valueOf(auction.getStarting_price()));
            if (auction.getMaxPrice() != null) {
                stmt.setBigDecimal(7, auction.getMaxPrice());
            } else {
                stmt.setNull(7, java.sql.Types.NUMERIC);
            }
            stmt.setInt(8, Math.max(1, auction.getQuantity()));
            if (auction.getCostPrice() != null) {
                stmt.setBigDecimal(9, auction.getCostPrice());
            } else {
                stmt.setNull(9, java.sql.Types.NUMERIC);
            }
            if (auction.getDutchFloorPrice() != null) {
                stmt.setBigDecimal(10, auction.getDutchFloorPrice());
            } else {
                stmt.setNull(10, java.sql.Types.NUMERIC);
            }
            if (auction.getBuyItNowPrice() != null) {
                stmt.setBigDecimal(11, auction.getBuyItNowPrice());
            } else {
                stmt.setNull(11, java.sql.Types.NUMERIC);
            }
            // Never null: Auction defaults it to PRODUCT and normalises anything unrecognised
            // back to PRODUCT, so this can only ever be a value the CHECK constraint accepts.
            stmt.setString(12, auction.getListingKind());
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) throw new Exception("Failed at auction_details");
        }
    }

    /**
     * Batch-inserts the image rows. Insertion order matters: every listing surface picks its
     * thumbnail with {@code ORDER BY id LIMIT 1}, so the first filename in the list becomes the
     * card image.
     */
    private void insertAuctionImages(Connection conn, long auctionId, List<String> imageFilenames) throws Exception {
        if (imageFilenames == null || imageFilenames.isEmpty()) return;
        String sql = "INSERT INTO auction_images (auction_id, image_url, upload_date) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (String filename : imageFilenames) {
                stmt.setLong(1, auctionId);
                stmt.setString(2, filename);
                stmt.setTimestamp(3, now);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (BatchUpdateException e) {
            throw new Exception("Failed to save images to database", e);
        }
    }

    /** Batch-inserts the link rows that {@link AuctionTagsDAO} later reads back. */
    private void insertAuctionTags(Connection conn, long auctionId, List<Long> tags) throws Exception {
        if (tags == null || tags.isEmpty()) return;
        String sql = "INSERT INTO auction_tag_info (auction_id, tag_id) VALUES (?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Long tag : tags) {
                stmt.setLong(1, auctionId);
                stmt.setLong(2, tag);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (BatchUpdateException e) {
            throw new Exception("Failed to save tags to database", e);
        }
    }

    /**
     * Moderation state change with a whitelist. Only active, flagged and removed are accepted;
     * anything else throws rather than reaching the database, so the CHECK constraint is never the
     * first line of defence.
     */
    public boolean updateAuctionState(long auction_id, String value) throws Exception {
        String sqlString = "UPDATE auction SET moderation_state = ? WHERE auction_id = ?";
        if(value == null || value.isBlank())
        {
            return false;
        }
        try(Connection conn = DBUtil.connectDB())
        {
            try(PreparedStatement stmt = conn.prepareStatement(sqlString))
            {
                String status;
                switch(value.trim().toLowerCase())
                {
                    case("active"):
                    case("flagged"):
                    case("removed"):
                        status = value.trim().toLowerCase();
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid moderation state: " + value);
                }
                stmt.setString(1, status);
                stmt.setLong(2, auction_id);
                return stmt.executeUpdate() > 0;
            }
        }catch(Exception e)
        {
            throw new Exception("remove auction failed", e);
        }
    }

    /** Top 10 sellers by number of listings created, for the admin statistics page. */
    public List<TopStatistics> getTopAuctionCreator() throws Exception
    {
        String sqlString = "SELECT u.id, u.username, COUNT(a.auction_id) AS total_auctions " +
                "FROM auction a " +
                "JOIN users u ON a.seller_id = u.id " +
                "GROUP BY u.id, u.username " +
                "ORDER BY total_auctions DESC " +
                "LIMIT 10;";
        try(Connection conn = DBUtil.connectDB();
        PreparedStatement stmt = conn.prepareStatement(sqlString))
        {
            List<TopStatistics> result = new ArrayList<>();
            try(ResultSet rs = stmt.executeQuery())
            {
                while(rs.next())
                {
                    User temp = new User();
                    temp.setId((int) rs.getLong("id"));
                    temp.setUsername(rs.getString("username"));
                    TopStatistics tsTemp = new TopStatistics();
                    tsTemp.setUser(temp);
                    tsTemp.setAuction_count(rs.getInt("total_auctions"));
                    result.add(tsTemp);
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Top 10 sellers by money taken. Sums {@code winning_bid} per seller, filtered to auctions
     * that actually sold, so unsold listings contribute nothing and do not drag a seller down.
     */
    public List<TopStatistics> getTopSellerRevenue()throws Exception{
        String sqlString = "SELECT u.id, u.username, SUM(ad.winning_bid) AS total_revenue " +
                "FROM auction a " +
                "JOIN users u ON a.seller_id = u.id " +
                "JOIN auction_details ad ON a.auction_id = ad.id " +
                "WHERE ad.winning_bid IS NOT NULL " +
                "GROUP BY u.id, u.username " +
                "ORDER BY total_revenue DESC " +
                "LIMIT 10";
        try(Connection conn = DBUtil.connectDB();
        PreparedStatement stmt = conn.prepareStatement(sqlString))
        {
            List<TopStatistics> result = new ArrayList<>();
            try(ResultSet rs = stmt.executeQuery())
            {
                while(rs.next()){
                    User temp = new User();
                    TopStatistics tsTemp = new TopStatistics();
                    temp.setId((int) rs.getLong("id"));
                    temp.setUsername(rs.getString("username"));
                    tsTemp.setUser(temp);
                    tsTemp.setTotal_revenue(rs.getFloat("total_revenue"));
                    result.add(tsTemp);
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Listings matching an optional set of report filters, used by the admin report generator.
     *
     * <p>The SQL is assembled conditionally and the parameter index {@code i} advances only for
     * the clauses that were actually appended, which is how the bindings stay aligned with the
     * placeholders. Every filter value is still bound, never concatenated.</p>
     *
     * @param sellerUsername exact username, or null for all sellers
     * @param category       exact category name, or null for all categories
     * @param from           earliest creation time, or null for no lower bound
     * @param to             latest creation time, or null for no upper bound
     */
    public List<AdminListingRow> listForGenReport(String sellerUsername, String category, Instant from, Instant to) throws Exception {
        // "WHERE 1=1" is a placeholder that lets every optional clause start with AND, so the
        // builder does not need to track whether it is writing the first condition.
        StringBuilder sql = new StringBuilder(
                "SELECT a.auction_id, ad.title, u.username, a.moderation_state, a.date_created " +
                        "FROM auction a " +
                        "JOIN auction_details ad ON a.auction_id = ad.id " +
                        "JOIN users u ON a.seller_id = u.id " +
                        "WHERE 1=1 ");

        if (sellerUsername != null && !sellerUsername.isBlank())
            sql.append("AND u.username = ? ");
        if (category != null && !category.isBlank())
            sql.append("AND ad.category = ? ");
        if (from != null)
            sql.append("AND a.date_created >= ? ");
        if (to != null)
            sql.append("AND a.date_created <= ? ");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (sellerUsername != null && !sellerUsername.isBlank()) stmt.setString(i++, sellerUsername);
            if (category != null && !category.isBlank())             stmt.setString(i++, category);
            if (from != null)                                         stmt.setTimestamp(i++, Timestamp.from(from));
            if (to != null)                                           stmt.setTimestamp(i++, Timestamp.from(to));

            try (ResultSet rs = stmt.executeQuery()) {
                List<AdminListingRow> result = new ArrayList<>();
                while (rs.next()) {
                    AdminListingRow row = new AdminListingRow();
                    row.setAuctionId(rs.getLong("auction_id"));
                    row.setTitle(rs.getString("title"));
                    row.setSellerUsername(rs.getString("username"));
                    row.setModerationState(rs.getString("moderation_state"));
                    row.setListedDate(LocalDate.from(rs.getTimestamp("date_created").toInstant()));
                    result.add(row);
                }
                return result;
            }
        } catch (Exception e) {
            throw new Exception("Failed to retrieve listings for report", e);
        }
    }
}
