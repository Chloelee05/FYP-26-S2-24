package com.auction.dao;

import com.auction.model.admin.PlatformRules;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.logging.Logger;

/**
 * Data-access layer for the single {@code platform_rules} row (SCRUM-67).
 *
 * <p><b>Caching:</b> the rules are read on every bid and every listing creation but change
 * rarely, so the loaded snapshot is cached in-process for {@value #CACHE_TTL_MILLIS} ms.
 * {@link #update} clears the cache, so an admin's change takes effect immediately.</p>
 *
 * <p><b>Fallback:</b> a database that has not run {@code migration_platform_rules.sql} yet
 * has no such table. Rather than break bidding and listing, every read falls back to
 * {@link PlatformRules#defaults()} and logs a warning. The fallback is cached too, so a
 * broken or missing table costs one query per TTL window, not one per bid.</p>
 */
public class PlatformRulesDAO {

    private static final Logger LOGGER = Logger.getLogger(PlatformRulesDAO.class.getName());

    /** How long a loaded snapshot stays valid before it is re-read. */
    static final long CACHE_TTL_MILLIS = 30_000L;

    private static final String SELECT_SQL =
            "SELECT min_bid_increment, max_auction_duration_days, updated_at, updated_by "
            + "FROM platform_rules WHERE id = 1";

    private static volatile PlatformRules cached;
    private static volatile long cachedAtMillis;

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * The current rules, served from cache when fresh. Opens its own connection when the
     * cache has expired. Never throws — falls back to {@link PlatformRules#defaults()}.
     */
    public PlatformRules get() {
        PlatformRules snapshot = fresh();
        if (snapshot != null) return snapshot;

        try (Connection conn = DBUtil.connectDB()) {
            return store(load(conn));
        } catch (Exception e) {
            LOGGER.warning("PlatformRulesDAO: could not load platform rules (" + e.getMessage()
                    + "); using defaults.");
            return store(PlatformRules.defaults());
        }
    }

    /**
     * Same as {@link #get()} but reuses an already-open connection — used by DAOs that are
     * mid-transaction and must not take a second connection from the pool.
     *
     * <p>The read is wrapped in a savepoint: on a database without
     * {@code migration_platform_rules.sql} the failed SELECT would otherwise abort the
     * caller's transaction and take the bid down with it. Rolling back to the savepoint
     * leaves the transaction usable and the defaults apply.</p>
     */
    public PlatformRules get(Connection conn) {
        PlatformRules snapshot = fresh();
        if (snapshot != null) return snapshot;

        Savepoint savepoint = null;
        try {
            if (!conn.getAutoCommit()) savepoint = conn.setSavepoint("platform_rules_read");
            PlatformRules loaded = load(conn);
            if (savepoint != null) conn.releaseSavepoint(savepoint);
            return store(loaded);
        } catch (Exception e) {
            if (savepoint != null) {
                try { conn.rollback(savepoint); } catch (SQLException ignored) { }
            }
            LOGGER.warning("PlatformRulesDAO: could not load platform rules (" + e.getMessage()
                    + "); using defaults.");
            return store(PlatformRules.defaults());
        }
    }

    /** Reads the configuration row; returns the defaults when the row is absent. */
    private PlatformRules load(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                LOGGER.warning("PlatformRulesDAO: platform_rules row 1 is missing; using defaults.");
                return PlatformRules.defaults();
            }
            return mapRow(rs);
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Saves both rules onto the singleton row and returns the stored snapshot.
     * The caller is responsible for validating the values first
     * ({@link PlatformRules#violationForBidIncrement}, {@link PlatformRules#violationForDurationDays}).
     *
     * @param minBidIncrement        new minimum bid increment
     * @param maxAuctionDurationDays new maximum auction duration, in days
     * @param adminId                admin performing the change, or {@code null}
     * @return the rules as stored
     */
    public PlatformRules update(BigDecimal minBidIncrement, int maxAuctionDurationDays, Integer adminId) {
        String sql =
                "INSERT INTO platform_rules (id, min_bid_increment, max_auction_duration_days, "
                + "updated_at, updated_by) VALUES (1, ?, ?, CURRENT_TIMESTAMP, ?) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "  min_bid_increment         = EXCLUDED.min_bid_increment, "
                + "  max_auction_duration_days = EXCLUDED.max_auction_duration_days, "
                + "  updated_at                = CURRENT_TIMESTAMP, "
                + "  updated_by                = EXCLUDED.updated_by";

        try (Connection conn = DBUtil.connectDB()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBigDecimal(1, minBidIncrement);
                ps.setInt(2, maxAuctionDurationDays);
                if (adminId != null) ps.setInt(3, adminId);
                else ps.setNull(3, Types.BIGINT);
                ps.executeUpdate();
            }
            clearCache();
            return store(load(conn));
        } catch (Exception e) {
            clearCache();
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Cache helpers
    // -------------------------------------------------------------------------

    /** Drops the cached snapshot so the next read hits the database. */
    public static void clearCache() {
        cached = null;
        cachedAtMillis = 0L;
    }

    private static PlatformRules fresh() {
        PlatformRules snapshot = cached;
        if (snapshot == null) return null;
        return (System.currentTimeMillis() - cachedAtMillis) < CACHE_TTL_MILLIS ? snapshot : null;
    }

    private static PlatformRules store(PlatformRules rules) {
        cached = rules;
        cachedAtMillis = System.currentTimeMillis();
        return rules;
    }

    private static PlatformRules mapRow(ResultSet rs) throws SQLException {
        BigDecimal increment = rs.getBigDecimal("min_bid_increment");
        int durationDays = rs.getInt("max_auction_duration_days");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        int updatedByValue = rs.getInt("updated_by");
        Integer updatedBy = rs.wasNull() ? null : updatedByValue;
        return new PlatformRules(
                increment,
                durationDays,
                updatedAt != null ? updatedAt.toInstant() : null,
                updatedBy);
    }
}
