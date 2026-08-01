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
            outboxDAO.enqueue(userId, alert.eventType, alert.auctionId, alert.body, alert.dedupeKey,
                    alert.initialDelaySeconds);
        } catch (Exception e) {
            LOG.fine("Telegram alert not queued: " + e.getMessage());
        }
    }

    /**
     * Drops a coalescing price alert for {@code auctionId} that is still waiting out its
     * cooldown, because the auction has concluded and the message is now false.
     *
     * <p>Without this the seller of a hotly contested listing would get the result, then a
     * minute later "your listing is at $410, bidding is live — no action needed". The queue
     * cannot notice that on its own: the row was correct when it was written.</p>
     */
    static void cancelPriceAlerts(long auctionId) {
        if (!TelegramConfig.isConfigured()) {
            return;
        }
        try {
            outboxDAO.cancelPending("PRICE:" + auctionId, "auction concluded before delivery");
        } catch (Exception e) {
            LOG.fine("Stale Telegram price alert not cancelled: " + e.getMessage());
        }
    }

    /**
     * {@code telegram_enabled} is the master switch — off silences everything without the
     * user having to unlink. An unrecognised event type is allowed through, so adding one
     * only needs a column when it should be independently opt-out-able.
     *
     * <p>{@code SELLER_PRICE} is the only event whose column defaults to false. It is the
     * only one that can fire repeatedly while a member is doing nothing, so it is opt-in:
     * a seller who never opens the Notifications tab is never messaged about live bidding.
     * That default lives in the {@code telegram_seller_price} column, so this method needs
     * no special case for it — it simply reads what the user has, and
     * {@link NotificationDAO.TelegramPreferences#defaults()} agrees with the schema.</p>
     *
     * <p>Every post-sale event shares {@code telegram_order_updates}. They are listed
     * individually rather than left to the {@code default} branch so that the gate is
     * explicit: an event that reaches this method without a case is one nobody chose to
     * make opt-out-able, which should be visible here rather than inferred.</p>
     */
    static boolean allowed(NotificationDAO.TelegramPreferences p, String eventType) {
        if (!p.enabled) {
            return false;
        }
        switch (eventType) {
            case "OUTBID":           return p.outbid;
            case "WON":              return p.won;
            case "LOST":             return p.lost;
            case "SELLER_PRICE":     return p.sellerPrice;
            case "SELLER_RESULT":    return p.sellerResult;
            case "ORDER_PAYMENT":
            case "ORDER_PAID":
            case "ORDER_SHIPPED":
            case "ORDER_IN_TRANSIT":
            case "ORDER_DELIVERED":
            case "ORDER_COMPLETED":
            case "REFUND_REQUESTED":
            case "REFUND_APPROVED":
            case "REFUND_REJECTED":  return p.orderUpdates;
            default:                 return true;
        }
    }
}
