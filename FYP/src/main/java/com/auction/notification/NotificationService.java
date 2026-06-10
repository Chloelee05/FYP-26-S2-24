package com.auction.notification;

import com.auction.dao.NotificationDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.util.DBUtil;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Logger;

/**
 * Generates in-app notifications (and best-effort email when SMTP is configured)
 * for bidding results, account decisions, Q&amp;A replies and orders.
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

    /** Notifies the previous top bidder (runner-up) that they have been outbid. */
    public static void notifyOutbid(long auctionId, int newLeaderId) {
        safe(() -> {
            Integer runnerUp = runnerUpBidder(auctionId, newLeaderId);
            if (runnerUp == null) return;
            String title = auctionTitle(auctionId);
            create(runnerUp, "OUTBID",
                    "You've been outbid on \"" + title + "\".",
                    "/auction/" + auctionId,
                    "You've been outbid",
                    "Someone placed a higher bid on \"" + title + "\". Visit AuctionHub to bid again.");
        });
    }

    /** Notifies the winner that they won, and the seller that their item sold. */
    public static void notifyAuctionWon(long auctionId, int winnerId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(winnerId, "WON",
                    "Congratulations! You won \"" + title + "\". Complete payment to finish the transaction.",
                    "/auction/" + auctionId,
                    "You won an auction",
                    "You won \"" + title + "\" on AuctionHub. Log in to complete payment.");
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

    /** Notifies the seller that the buyer confirmed receipt. */
    public static void notifySellerReceiptConfirmed(long auctionId, int sellerId) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            create(sellerId, "ORDER_COMPLETED",
                    "The buyer confirmed receipt for \"" + title + "\".",
                    "/profile",
                    "Buyer confirmed receipt",
                    "The buyer confirmed receipt for \"" + title + "\" on AuctionHub.");
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
        safe(() -> {
            String title = auctionTitle(auctionId);
            String verb = approved ? "approved" : "declined";
            create(buyerId, approved ? "REFUND_APPROVED" : "REFUND_REJECTED",
                    "The seller " + verb + " your refund request for \"" + title + "\".",
                    "/profile",
                    "Refund " + verb,
                    "The seller " + verb + " your refund request for \"" + title + "\" on AuctionHub.");
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

    // ── Core create (in-app + optional email) ───────────────────────────────────

    private static void create(int userId, String type, String message, String link,
                               String emailSubject, String emailBody) {
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
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

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

    /** Highest bidder other than {@code excludeUserId}, i.e. the user just outbid. */
    private static Integer runnerUpBidder(long auctionId, int excludeUserId) {
        String sql = "SELECT user_id FROM bids WHERE auction_id = ? AND user_id <> ? "
                + "ORDER BY bid_amount DESC, bid_time DESC LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, excludeUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            LOG.fine("runnerUpBidder lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Exception e) { LOG.warning("Notification failed: " + e.getMessage()); }
    }
}
