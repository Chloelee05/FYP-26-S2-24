package com.auction.notification;

import com.auction.dao.NotificationDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.telegram.TelegramAlerts;
import com.auction.util.DBUtil;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates in-app notifications for bidding results, account decisions, Q&amp;A replies and
 * orders, plus best-effort email when SMTP is configured and a queued Telegram push when
 * the user has connected the bot.
 *
 * <p>All three channels funnel through one private {@code create}, so an event that a user
 * has opted out of is suppressed everywhere at once and a channel cannot drift from the
 * others.</p>
 *
 * <p>All methods are best-effort and never throw: a notification failure must not
 * break the primary action (placing a bid, approving a user, etc.).</p>
 */
public final class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());
    private static final NotificationDAO notificationDAO = new NotificationDAO();
    private static final UserDAO userDAO = new UserDAO();

    private NotificationService() { }

    // ── Public event hooks ────────────────────────────────────────────────────

    /**
     * Notifies {@code displacedUserId} that they no longer hold the top bid.
     *
     * <p>The recipient is passed in rather than derived here. It used to be looked up as
     * "highest bidder who is not the caller", which silently named the wrong person whenever
     * a proxy auto-bid counter-bid inside the same transaction: the auto-bidder was both the
     * highest other bidder and the current leader, so the winner was told they had lost
     * while the bidder they had actually displaced heard nothing. Only the caller, which can
     * see the leader before and after auto-bid resolution, knows who was really displaced —
     * see {@code BidDAO.BidOutcome#displacedBidderId()}.</p>
     *
     * <p>Privacy: the message never names who outbid them. That identity is personal data
     * and, in a live auction, tells the recipient how much further their opponent is likely
     * to go.</p>
     */
    public static void notifyOutbid(long auctionId, int displacedUserId) {
        safe(() -> {
            AuctionSummary summary = auctionSummary(auctionId);
            create(displacedUserId, "OUTBID",
                    "You've been outbid on \"" + summary.title + "\".",
                    "/auction/" + auctionId,
                    "You've been outbid",
                    "Someone placed a higher bid on \"" + summary.title
                            + "\". Visit AuctionHub to bid again.",
                    TelegramAlerts.outbid(auctionId, displacedUserId, summary.title, summary.topBid));
        });
    }

    /** Notifies the seller that a new ascending bid was placed on their auction. */
    public static void notifySellerNewBid(long auctionId, java.math.BigDecimal bidAmount) {
        safe(() -> {
            Integer sellerId = sellerOf(auctionId);
            if (sellerId == null) return;
            String title = auctionTitle(auctionId);
            String amount = bidAmount != null ? formatMoney(bidAmount) : "$?";
            create(sellerId, "NEW_BID",
                    "New bid of " + amount + " on \"" + title + "\".",
                    "/auction/" + auctionId,
                    "New bid on your auction",
                    "Someone placed a bid of " + amount + " on \"" + title + "\" on AuctionHub.");
        });
    }

    /** Notifies the seller that their auction ended without a winner (can relist). */
    public static void notifyAuctionEndedUnsold(long auctionId) {
        safe(() -> {
            Integer sellerId = sellerOf(auctionId);
            if (sellerId == null) return;
            String title = auctionTitle(auctionId);
            create(sellerId, "AUCTION_ENDED",
                    "Your auction \"" + title + "\" ended without a sale. You can relist it from your seller dashboard.",
                    "/seller/dashboard",
                    "Auction ended unsold",
                    "Your auction \"" + title + "\" ended without a winner on AuctionHub. You can relist it from your seller dashboard.");
        });
    }

    /** Notifies the winner that they won, and the seller that their item sold. */
    public static void notifyAuctionWon(long auctionId, int winnerId) {
        safe(() -> {
            AuctionSummary summary = auctionSummary(auctionId);
            String title = summary.title;
            create(winnerId, "WON",
                    "Congratulations! You won \"" + title + "\". Complete payment to finish the transaction.",
                    "/auction/" + auctionId,
                    "You won an auction",
                    "You won \"" + title + "\" on AuctionHub. Log in to complete payment.",
                    TelegramAlerts.won(auctionId, winnerId, title, summary.topBid));
            Integer sellerId = sellerOf(auctionId);
            if (sellerId != null) {
                create(sellerId, "SOLD",
                        "Your item \"" + title + "\" has sold.",
                        "/seller/dashboard",
                        "Your item sold",
                        "Your auction \"" + title + "\" has a winner on AuctionHub.");
            }
        });
    }

    /** Same as {@link #notifyAuctionWon} but skips if the buyer already has a WON notification for this auction. */
    public static void notifyAuctionWonIfAbsent(long auctionId, int winnerId) {
        safe(() -> {
            String link = "/auction/" + auctionId;
            if (notificationDAO.exists(winnerId, "WON", link)) return;
            notifyAuctionWon(auctionId, winnerId);
        });
    }

    /**
     * Tells everyone who bid on a concluded auction, other than the winner, that it closed
     * without them.
     *
     * <p>An auction can conclude four ways — the clock running out, the seller declaring a
     * winner, a Buy It Now, or a Dutch acceptance — and time expiry is itself re-entrant,
     * since {@code AuctionFinalizer} is called both by the 60-second sweep and lazily by any
     * page that loads an ended auction. Rather than making each of those paths remember
     * whether it has already announced the result, every recipient is deduplicated on
     * {@code (userId, "LOST", link)}, the same way {@link #notifyAuctionWonIfAbsent} is.
     * The Telegram leg carries {@code LOST:{auctionId}:{userId}} into the outbox's own
     * dedupe index, so a re-entry that races the first one still cannot double-send.</p>
     *
     * <p>Privacy: the message carries the title and the final price only. Naming the winner
     * would disclose one member's purchase to every rival bidder, which is neither necessary
     * for the notification to be useful nor something the loser has any right to know.</p>
     *
     * @param winnerId the excluded winner, or a non-positive value when the auction ended
     *                 with no winner at all
     */
    public static void notifyAuctionLost(long auctionId, int winnerId) {
        safe(() -> {
            AuctionSummary summary = auctionSummary(auctionId);
            String link = "/auction/" + auctionId;
            for (int loserId : losingBidders(auctionId, winnerId)) {
                if (notificationDAO.exists(loserId, "LOST", link)) continue;
                create(loserId, "LOST",
                        "\"" + summary.title + "\" closed without you. The winning bid was higher than yours.",
                        link,
                        "An auction closed without you",
                        "\"" + summary.title + "\" has ended on AuctionHub and your bid was not the winning one. "
                                + "Browse similar listings to keep looking.",
                        TelegramAlerts.lost(auctionId, loserId, summary.title, summary.topBid));
            }
        });
    }

    /**
     * Notifies a watcher that an auction on their watchlist is ending soon.
     * Deduplicated per user + auction so the scheduler can run repeatedly.
     */
    public static void notifyEndingSoon(int userId, long auctionId, String title) {
        safe(() -> {
            String link = "/auction/" + auctionId;
            if (notificationDAO.exists(userId, "ENDING_SOON", link)) return;
            String t = (title == null || title.isBlank()) ? auctionTitle(auctionId) : title;
            create(userId, "ENDING_SOON",
                    "\"" + t + "\" on your watchlist is ending soon. Place your bid before it closes!",
                    link,
                    "Auction ending soon",
                    "\"" + t + "\" on your AuctionHub watchlist is ending soon. "
                            + "Visit the auction now to place a last-minute bid.");
        });
    }

    /** Notifies the asking buyer that a seller answered their question. */
    public static void notifyQuestionAnswered(int askerUserId, long auctionId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(askerUserId, "QA_REPLY",
                    "The seller replied to your question on \"" + title + "\".",
                    "/auction/" + auctionId,
                    "Seller replied to your question",
                    "The seller answered your question on \"" + title + "\".");
        });
    }

    /** Resolves the asker + auction from a question id, then notifies the asker of a reply. */
    public static void notifyQuestionAnsweredByQuestionId(long questionId) {
        safe(() -> {
            String sql = "SELECT auction_id, asker_user_id FROM auction_questions WHERE id = ?";
            try (Connection conn = DBUtil.connectDB();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, questionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        notifyQuestionAnswered(rs.getInt("asker_user_id"), rs.getLong("auction_id"));
                    }
                }
            } catch (Exception e) {
                LOG.fine("notifyQuestionAnsweredByQuestionId failed: " + e.getMessage());
            }
        });
    }

    public static void notifyAccountApproved(int userId) {
        safe(() -> create(userId, "ACCOUNT_APPROVED",
                "Your account has been approved. Welcome to AuctionHub!",
                "/login",
                "Your AuctionHub account is approved",
                "An administrator approved your registration. You can now sign in."));
    }

    public static void notifyAccountRejected(int userId) {
        safe(() -> create(userId, "ACCOUNT_REJECTED",
                "Your registration was not approved. Please contact support for details.",
                null,
                "AuctionHub registration update",
                "Your AuctionHub registration was not approved."));
    }

    /** Notifies the seller that the winning buyer has paid. */
    public static void notifyOrderPaid(long auctionId, int sellerId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(sellerId, "ORDER_PAID",
                    "Payment received for \"" + title + "\". You can now arrange delivery.",
                    "/seller/dashboard",
                    "Payment received",
                    "The winning buyer paid for \"" + title + "\" on AuctionHub.");
        });
    }

    /**
     * Sends the buyer a payment confirmation and itemised receipt (in-app + email) after
     * a successful order payment. {@code paymentLabel} describes the method used
     * (e.g. "Visa ****4242", "PayPal (a@b.com)"); pass null when unknown.
     */
    public static void notifyBuyerPaymentReceipt(long orderId, int buyerId, String paymentLabel) {
        safe(() -> {
            OrderReceipt r = orderReceipt(orderId);
            String title = r != null ? r.title : "your order";
            String amount = r != null && r.amount != null ? formatMoney(r.amount) : "";
            String method = (paymentLabel == null || paymentLabel.isBlank()) ? "your payment method" : paymentLabel;

            String inApp = "Payment confirmed for \"" + title + "\""
                    + (amount.isEmpty() ? "" : " (" + amount + ")") + ". A receipt has been emailed to you.";

            StringBuilder body = new StringBuilder();
            body.append("Thank you for your payment on AuctionHub.\n\n");
            body.append("Payment confirmation / receipt\n");
            body.append("------------------------------\n");
            body.append("Order #: ").append(orderId).append('\n');
            body.append("Item: ").append(title).append('\n');
            if (!amount.isEmpty()) body.append("Amount paid: ").append(amount).append('\n');
            body.append("Payment method: ").append(method).append('\n');
            body.append("Status: PAID\n\n");
            body.append("Keep this email as proof of your transaction. "
                    + "You can track delivery and confirm receipt from your Orders page.");

            create(buyerId, "PAYMENT_RECEIPT", inApp, "/profile",
                    "Your AuctionHub payment receipt (Order #" + orderId + ")", body.toString());
        });
    }

    /** Notifies the buyer that the package was marked delivered — confirm receipt when ready. */
    public static void notifyOrderDelivered(long auctionId, int buyerId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(buyerId, "ORDER_DELIVERED",
                    "Your order \"" + title + "\" was marked delivered. Please confirm receipt in Orders.",
                    "/profile",
                    "Package delivered",
                    "Your order \"" + title + "\" was marked delivered on AuctionHub. Confirm receipt when you receive it.");
        });
    }

    /** Notifies the seller that the buyer confirmed receipt; payment/earnings are complete. */
    public static void notifySellerReceiptConfirmed(long auctionId, int sellerId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(sellerId, "ORDER_COMPLETED",
                    "The buyer confirmed receipt for \"" + title
                            + "\". Payment is complete and funds are reflected in your earnings summary.",
                    "/seller/dashboard",
                    "Payment complete — receipt confirmed",
                    "The buyer confirmed receipt for \"" + title
                            + "\" on AuctionHub. Payment is complete and the sale is reflected in your earnings.");
        });
    }

    /** Notifies the seller that the buyer requested a refund. */
    public static void notifySellerRefundRequested(long auctionId, int sellerId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(sellerId, "REFUND_REQUESTED",
                    "The buyer requested a refund for \"" + title + "\". Review it in your Orders.",
                    "/profile",
                    "Refund requested",
                    "The buyer requested a refund for \"" + title + "\" on AuctionHub.");
        });
    }

    /** Notifies the buyer that the seller approved or declined their refund request. */
    public static void notifyBuyerRefundResolved(long auctionId, int buyerId, boolean approved) {
        notifyBuyerRefundResolved(auctionId, buyerId, approved, "The seller");
    }

    /** Same as above, but names a different decision maker (e.g. "An AuctionHub admin"). */
    public static void notifyBuyerRefundResolved(long auctionId, int buyerId, boolean approved, String actor) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            String verb = approved ? "approved" : "declined";
            create(buyerId, approved ? "REFUND_APPROVED" : "REFUND_REJECTED",
                    actor + " " + verb + " your refund request for \"" + title + "\".",
                    "/profile",
                    "Refund " + verb,
                    actor + " " + verb + " your refund request for \"" + title + "\" on AuctionHub.");
        });
    }

    /** Notifies the order counterparty that a new direct message was received. */
    public static void notifyOrderMessage(long auctionId, int recipientId, String senderName) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            String who = (senderName == null || senderName.isBlank()) ? "Someone" : senderName;
            create(recipientId, "ORDER_MESSAGE",
                    who + " sent you a message about \"" + title + "\".",
                    "/messages",
                    "New message",
                    who + " sent you a message about \"" + title + "\" on AuctionHub.");
        });
    }

    /** Notifies all admins that a new account is awaiting approval. */
    public static void notifyAdminsPendingRegistration(String username) {
        safe(() -> {
            String who = (username == null || username.isBlank()) ? "A user" : username;
            notifyAllAdmins("ADMIN_PENDING_USER",
                    "New registration pending approval: " + who,
                    "/admin/users");
        });
    }

    /** Notifies all admins that a user sent a support message. */
    public static void notifyAdminsSupportMessage(long threadId, String username, String bodyPreview) {
        safe(() -> {
            String who = (username == null || username.isBlank()) ? "User" : username;
            String preview = previewText(bodyPreview, 100);
            notifyAllAdmins("ADMIN_SUPPORT",
                    who + ": " + preview,
                    "/admin/chat?thread=" + threadId);
        });
    }

    /** Notifies all admins of a new account report. */
    public static void notifyAdminsAccountReport(String reporterName, String reason) {
        safe(() -> notifyAllAdmins("ADMIN_ACCOUNT_REPORT",
                "Account report from " + safeName(reporterName) + ": " + previewText(reason, 80),
                "/admin/reports"));
    }

    /** Notifies all admins of a new listing report. */
    public static void notifyAdminsListingReport(long auctionId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            notifyAllAdmins("ADMIN_LISTING_REPORT",
                    "New listing report: \"" + title + "\"",
                    "/admin/reports");
        });
    }

    private static void notifyAllAdmins(String type, String message, String link) {
        for (int adminId : userDAO.listAdminUserIds()) {
            notificationDAO.create(adminId, type, message, link);
        }
    }

    private static String previewText(String text, int maxLen) {
        if (text == null) return "";
        String s = text.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "(no text)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private static String safeName(String name) {
        return (name == null || name.isBlank()) ? "Someone" : name.trim();
    }

    // ── Core create (in-app + optional email) ───────────────────────────────────

    private static void create(int userId, String type, String message, String link,
                               String emailSubject, String emailBody) {
        create(userId, type, message, link, emailSubject, emailBody, null);
    }

    /**
     * The single funnel every notification passes through, now for three channels.
     *
     * <p>{@code allowedByPreference} is checked once, here, and decides for all of them: if
     * an event is suppressed it is suppressed everywhere, and a new channel cannot drift
     * from the others because there is only one place the decision is made. That is also why
     * the Telegram enqueue lives here rather than beside each {@code notify*} caller — the
     * legacy JSP bid path reaches the same {@link #notifyOutbid} and therefore gets Telegram
     * delivery without knowing Telegram exists.</p>
     *
     * @param telegram the Telegram body for this event, or {@code null} for the event types
     *                 that have no push equivalent (admin alerts, receipts, order chatter)
     */
    private static void create(int userId, String type, String message, String link,
                               String emailSubject, String emailBody,
                               TelegramAlerts.Alert telegram) {
        if (!allowedByPreference(userId, type)) return;
        notificationDAO.create(userId, type, message, link);
        if (MailConfig.isSmtpConfigured()) {
            try {
                User u = userDAO.getUserById(userId);
                if (u != null && u.getEmail() != null && !u.getEmail().isBlank()) {
                    OtpMailer.sendNotification(u.getEmail(), emailSubject, emailBody);
                }
            } catch (Exception e) {
                LOG.fine("Notification email skipped: " + e.getMessage());
            }
        }
        TelegramNotifier.enqueue(userId, telegram);
    }

    /**
     * Checks the user's notification preferences for opt-out-able event types
     * (outbid / ending soon / auction won). All other types are always sent.
     */
    private static boolean allowedByPreference(int userId, String type) {
        if (type == null) return true;
        try {
            switch (type) {
                case "OUTBID":      return notificationDAO.getUserPreferences(userId).outbid;
                case "ENDING_SOON": return notificationDAO.getUserPreferences(userId).endingSoon;
                case "WON":         return notificationDAO.getUserPreferences(userId).wonAuction;
                default:            return true;
            }
        } catch (Exception e) {
            LOG.fine("Preference lookup failed (defaulting to send): " + e.getMessage());
            return true;
        }
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    /** The two facts every bid-related alert needs: what it is and what it costs. */
    private static final class AuctionSummary {
        final String title;
        /** Highest bid so far, i.e. the price to beat or the price it closed at. */
        final java.math.BigDecimal topBid;

        AuctionSummary(String title, java.math.BigDecimal topBid) {
            this.title = title;
            this.topBid = topBid;
        }
    }

    /** Title and current top bid in one round trip, since every caller needs both. */
    private static AuctionSummary auctionSummary(long auctionId) {
        String sql = "SELECT d.title, (SELECT MAX(bid_amount) FROM bids WHERE auction_id = d.id) AS top_bid "
                + "FROM auction_details d WHERE d.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String title = rs.getString("title");
                    return new AuctionSummary(
                            (title == null || title.isBlank()) ? "your item" : title,
                            rs.getBigDecimal("top_bid"));
                }
            }
        } catch (Exception e) {
            LOG.fine("auctionSummary lookup failed: " + e.getMessage());
        }
        return new AuctionSummary("your item", null);
    }

    private static String auctionTitle(long auctionId) {
        String sql = "SELECT title FROM auction_details WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            LOG.fine("auctionTitle lookup failed: " + e.getMessage());
        }
        return "your item";
    }

    /** Lightweight order fields needed to build a payment receipt. */
    private static final class OrderReceipt {
        final String title;
        final java.math.BigDecimal amount;
        OrderReceipt(String title, java.math.BigDecimal amount) { this.title = title; this.amount = amount; }
    }

    private static OrderReceipt orderReceipt(long orderId) {
        String sql = "SELECT d.title, o.amount FROM orders o "
                + "JOIN auction_details d ON d.id = o.auction_id WHERE o.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new OrderReceipt(rs.getString("title"), rs.getBigDecimal("amount"));
            }
        } catch (Exception e) {
            LOG.fine("orderReceipt lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static String formatMoney(java.math.BigDecimal amount) {
        return "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static Integer sellerOf(long auctionId) {
        String sql = "SELECT seller_id FROM auction WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            LOG.fine("sellerOf lookup failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Every distinct bidder on the auction except {@code winnerId}. One row per person
     * however many times they bid, so a buyer who was outbid five times is told once.
     */
    private static List<Integer> losingBidders(long auctionId, int winnerId) {
        String sql = "SELECT DISTINCT user_id FROM bids WHERE auction_id = ? AND user_id <> ?";
        List<Integer> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, winnerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (Exception e) {
            LOG.fine("losingBidders lookup failed: " + e.getMessage());
        }
        return out;
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Exception e) { LOG.warning("Notification failed: " + e.getMessage()); }
    }
}
