package com.auction.listener;

import com.auction.model.AuctionStatus;
import com.auction.util.AuctionFinalizer;
import com.auction.util.DBUtil;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically finalizes auctions whose end time has passed and notifies winning buyers.
 */
@WebListener
public class AuctionExpiryListener implements ServletContextListener {

    private ScheduledExecutorService scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-expiry");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runExpiryPass, 30, 60, TimeUnit.SECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void runExpiryPass() {
        try {
            List<Long> ids = listExpiredActiveAuctionIds();
            for (Long id : ids) {
                AuctionFinalizer.finalizeIfExpiredAndNotify(id);
            }
        } catch (Exception ignored) {
            // best-effort background task
        }
    }

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
