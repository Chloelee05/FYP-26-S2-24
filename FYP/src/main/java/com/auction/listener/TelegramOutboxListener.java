package com.auction.listener;

import com.auction.telegram.TelegramOutboxWorker;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drains the Telegram delivery outbox in the background, following the same shape as
 * {@link AuctionExpiryListener}: one daemon thread from a single-thread scheduler, shut
 * down with the context.
 *
 * <p><b>Exactly one thread, on purpose.</b> The instance runs on 512MB and half a CPU with
 * a fixed ten-connection pool ({@code DBUtil}), and Telegram only accepts about one message
 * per second per chat anyway — so a second sender would contend for connections without
 * being allowed to deliver anything faster.</p>
 *
 * <p>The first pass waits 20 seconds so it does not compete with the rest of startup, then
 * runs 10 seconds after each previous pass finishes. {@code scheduleWithFixedDelay} (not
 * {@code AtFixedRate}) is what guarantees passes cannot overlap when one runs long because
 * it is pacing a full batch.</p>
 */
@WebListener
public class TelegramOutboxListener implements ServletContextListener {

    private static final long INITIAL_DELAY_SECONDS = 20;
    private static final long FIXED_DELAY_SECONDS = 10;

    private ScheduledExecutorService scheduler;
    private final TelegramOutboxWorker worker = new TelegramOutboxWorker();

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telegram-outbox");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOutboxPass,
                INITIAL_DELAY_SECONDS, FIXED_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void runOutboxPass() {
        try {
            worker.runOnce();
        } catch (Exception ignored) {
            // Best-effort background task: an exception escaping here would cancel the
            // schedule permanently, so nothing is allowed out.
        }
    }
}
