package com.auction.listener;

import com.auction.dao.OrderDAO;
import com.auction.dao.PlatformSettingsDAO;
import com.auction.dao.WatchlistDAO;
import com.auction.model.AuctionStatus;
import com.auction.model.profile.WatchlistRow;
import com.auction.notification.NotificationService;
import com.auction.util.AuctionFinalizer;
import com.auction.util.DBUtil;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically finalizes auctions whose end time has passed and notifies winning buyers.
 *
 * <p>This is the clock of the whole auction lifecycle. An auction ends because its
 * {@code date_end} passed, not because anyone visited a page, so the work cannot hang off an
 * HTTP request: if the last bidder closed the tab, nothing would ever close the auction.
 * A daemon thread started with the servlet context runs a sweep every 60 seconds, and each
 * sweep does four independent jobs: start scheduled auctions, finalise expired ones through
 * {@link AuctionFinalizer}, send "ending soon" alerts to watchers, and auto-cancel orders whose
 * payment window has run out.</p>
 *
 * <p>Each job is wrapped in its own try/catch so a failure in one (a dropped DB connection, a
 * mail server that will not answer) does not stop the other three, and so no exception escapes
 * into the scheduler. That last part matters: {@code scheduleWithFixedDelay} cancels the task
 * permanently if the run throws, which would silently freeze every auction on the platform.</p>
 */
@WebListener
public class AuctionExpiryListener implements ServletContextListener {

    private ScheduledExecutorService scheduler;

    /**
     * Starts the sweep on a single daemon thread when the web application comes up. The first
     * pass waits 30 seconds so it does not compete with the rest of startup, then each pass
     * begins 60 seconds after the previous one finished. Fixed delay rather than fixed rate
     * means two sweeps can never overlap and race over the same auction rows. The thread is a
     * daemon so it cannot hold the JVM open during shutdown.
     */
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-expiry");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runExpiryPass, 30, 60, TimeUnit.SECONDS);
    }

    /** Stops the sweep when the application is undeployed, so a redeploy does not leave two
     *  schedulers competing for the same auction rows. */
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * One tick of the sweep. The four jobs run in this order on purpose: an auction has to be
     * ACTIVE before it can expire, and an order has to exist before its payment window can
     * lapse. Everything here is best effort, so a job that fails is skipped until the next tick.
     */
    private void runExpiryPass() {
        try {
            // Activate any PENDING auctions whose start time has arrived.
            activatePendingAuctions();
        } catch (Exception ignored) { }

        try {
            List<Long> ids = listExpiredActiveAuctionIds();
            for (Long id : ids) {
                AuctionFinalizer.finalizeIfExpiredAndNotify(id);
            }
        } catch (Exception ignored) {
            // best-effort background task
        }

        try {
            // Watchlist "ending soon" alerts (deduplicated inside NotificationService).
            notifyEndingSoonWatchers();
        } catch (Exception ignored) { }

        try {
            // Auto-cancel unpaid winning bids past the configurable payment deadline.
            // Reuses this existing 60-second sweep rather than a second background thread.
            cancelOverdueUnpaidOrders();
        } catch (Exception ignored) { }
    }

    /**
     * Auto-cancellation of unpaid winning bids (anti-abuse / lifecycle feature). See
     * {@link OrderDAO#cancelOverduePendingOrders} for the full design decision (unsold
     * rather than re-award; grandfathering of orders that predate this feature).
     *
     * <p>Reads the deadline from platform settings rather than a constant so an admin can
     * change it without a redeploy, then notifies both sides of every order it cancelled.</p>
     */
    private static void cancelOverdueUnpaidOrders() {
        PlatformSettingsDAO settingsDAO = new PlatformSettingsDAO();
        // -1 sentinel: the migration has not been applied yet (or the row was deleted), so
        // there is no known cutoff. Skip the pass entirely rather than guessing one — an
        // unmigrated deployment must never auto-cancel anything.
        long effectiveSinceMs = settingsDAO.getLong(
                "order_payment_timeout_effective_since_epoch_ms", -1L);
        if (effectiveSinceMs < 0) return;

        int deadlineHours = settingsDAO.getInt("order_payment_deadline_hours", 48);
        Instant effectiveSince = Instant.ofEpochMilli(effectiveSinceMs);

        List<Long> cancelledOrderIds = new OrderDAO()
                .cancelOverduePendingOrders(Duration.ofHours(deadlineHours), effectiveSince);
        for (Long orderId : cancelledOrderIds) {
            NotificationService.notifyOrderPaymentTimeoutBuyer(orderId);
            NotificationService.notifyOrderPaymentTimeoutSeller(orderId);
        }
    }

    /**
     * Sends a reminder to every user watching an auction that is close to its end time.
     * The DAO decides which rows count as "ending soon"; repeat sends are suppressed inside
     * {@link NotificationService}, which is what stops a watcher being emailed once a minute
     * for the whole final hour.
     */
    private static void notifyEndingSoonWatchers() throws Exception {
        List<WatchlistRow> endingSoon = new WatchlistDAO().getEndingSoonWatchlistItems();
        for (WatchlistRow row : endingSoon) {
            NotificationService.notifyEndingSoon(row.getUserId(), row.getAuctionId(), row.getTitle());
        }
    }

    /**
     * Transitions PENDING auctions to ACTIVE when their {@code date_created} (= scheduled
     * start time) is now in the past. Extends the expiry listener without touching the
     * existing finalisation logic.
     *
     * <p>The update repeats {@code status_id = PENDING} in its WHERE clause even though the
     * select already filtered on it. That second check is what makes the operation safe if
     * anything changed the row between the two statements.</p>
     */
    private static void activatePendingAuctions() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT auction_id FROM auction "
                   + "WHERE status_id = ? "
                   + "  AND date_created IS NOT NULL "
                   + "  AND date_created <= CURRENT_TIMESTAMP")) {
            ps.setInt(1, AuctionStatus.PENDING.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("auction_id"));
            }
        }
        if (ids.isEmpty()) return;

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE auction SET status_id = ? "
                   + "WHERE auction_id = ? AND status_id = ?")) {
            for (Long id : ids) {
                ps.setInt(1, AuctionStatus.ACTIVE.getId());
                ps.setLong(2, id);
                ps.setInt(3, AuctionStatus.PENDING.getId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Returns the ids of auctions that are still ACTIVE but whose {@code date_end} has passed.
     * Only ids are selected; the finaliser reloads each auction inside its own transaction,
     * which keeps this query short and avoids holding a large result set open.
     */
    private static List<Long> listExpiredActiveAuctionIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT auction_id FROM auction "
                   + "WHERE status_id = ? AND date_end IS NOT NULL AND date_end <= CURRENT_TIMESTAMP")) {
            ps.setInt(1, AuctionStatus.ACTIVE.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("auction_id"));
            }
        }
        return ids;
    }
}
