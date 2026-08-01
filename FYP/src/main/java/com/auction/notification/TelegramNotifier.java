package com.auction.notification;

import com.auction.dao.NotificationDAO;
import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.TelegramOutboxDAO;
import com.auction.telegram.TelegramAlerts.Alert;
import com.auction.telegram.TelegramConfig;

import java.util.logging.Logger;

/**
 * The Telegram leg of {@link NotificationService#create}: decides whether an alert should
 * be delivered to this user and, if so, queues it.
 *
 * <p>Queuing rather than sending is the whole point — the caller is inside a bid request,
 * and {@link TelegramOutboxDAO#enqueue} is one INSERT. Delivery, retries and Telegram's
 * rate limits are the background worker's problem.</p>
 *
 * <p>Three gates, cheapest first: the deployment has a bot at all, the user has an active
 * link, and the user has not opted out of this event. Checking the link before queueing
 * keeps the outbox from filling with rows destined to be {@code SKIPPED} for the majority
 * of members who have never connected Telegram.</p>
 *
 * <p>Every path is best-effort: a notification is a side effect of the user's real action,
 * so nothing here is allowed to throw back into it.</p>
 */
final class TelegramNotifier {

    private static final Logger LOG = Logger.getLogger(TelegramNotifier.class.getName());

    private static TelegramLinkDAO linkDAO = new TelegramLinkDAO();
    private static TelegramOutboxDAO outboxDAO = new TelegramOutboxDAO();
    private static NotificationDAO notificationDAO = new NotificationDAO();

    private TelegramNotifier() {
    }

    /** Test hook. */
    static void setDaos(TelegramLinkDAO links, TelegramOutboxDAO outbox, NotificationDAO notifications) {
        linkDAO = links;
        outboxDAO = outbox;
        notificationDAO = notifications;
    }

    /** Queues {@code alert} for {@code userId} when all three gates allow it. */
    static void enqueue(int userId, Alert alert) {
        if (alert == null || !TelegramConfig.isConfigured()) {
            return;
        }
        try {
            if (linkDAO.findByUserId(userId) == null) {
                return;
            }
            if (!allowed(notificationDAO.getTelegramPreferences(userId), alert.eventType)) {
                return;
            }
            outboxDAO.enqueue(userId, alert.eventType, alert.auctionId, alert.body, alert.dedupeKey);
        } catch (Exception e) {
            LOG.fine("Telegram alert not queued: " + e.getMessage());
        }
    }

    /**
     * {@code telegram_enabled} is the master switch — off silences everything without the
     * user having to unlink. An unrecognised event type is allowed through, so adding one
     * only needs a column when it should be independently opt-out-able.
     */
    static boolean allowed(NotificationDAO.TelegramPreferences p, String eventType) {
        if (!p.enabled) {
            return false;
        }
        switch (eventType) {
            case "OUTBID": return p.outbid;
            case "WON":    return p.won;
            case "LOST":   return p.lost;
            default:       return true;
        }
    }
}
