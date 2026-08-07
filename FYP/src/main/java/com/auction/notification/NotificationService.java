package com.auction.notification;

import com.auction.dao.NotificationDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.telegram.TelegramAlerts;
import com.auction.telegram.TelegramConfig;
import com.auction.util.DBUtil;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *
 * <h2>Why neither slow channel runs on the request thread</h2>
 * <p>Only the in-app row is written synchronously, because it is a single INSERT and the
 * user expects the bell to update immediately. The other two channels each talk to a
 * third party over the network, and both were deliberately moved off the request thread
 * after they were found holding HTTP responses open.</p>
 * <p>Email is handed to a background executor, since sending is a full SMTP conversation
 * of connect, TLS, authenticate and deliver. A single bid could pay that cost twice, once
 * for the seller and once for the displaced bidder, before the bidder got their response
 * back. Telegram is queued as an outbox row and delivered later by
 * {@link com.auction.telegram.TelegramOutboxWorker}, which is also what lets the worker
 * respect Telegram's rate limits and retry a failed send. In both cases nothing in the
 * response depends on the message having actually left, so waiting for it bought
 * nothing.</p>
 */
public final class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());
    private static final NotificationDAO notificationDAO = new NotificationDAO();
    private static final UserDAO userDAO = new UserDAO();

    /**
     * Notification email is handed to this thread rather than sent on the caller's.
     *
     * <p>{@code Transport.send} is a full SMTP conversation — connect, TLS, auth, deliver —
     * and the caller is almost always a request thread, so a bid held its own HTTP response
     * open for the length of the send, twice over once the seller and the displaced bidder
     * were both being told. Nothing in the response depends on the mail having left, so it
     * does not belong in front of it.</p>
     *
     * <p>One daemon thread, for the same reason {@link com.auction.listener.TelegramOutboxListener}
     * uses one: the instance is small and shares a ten-connection pool, and mail this system
     * sends is measured in messages per minute. Delivery stays best-effort — a send that fails
     * after the response has gone is logged and dropped, exactly as it was before.</p>
     */
    private static final ExecutorService MAIL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notification-mail");
        t.setDaemon(true);
        return t;
    });

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

    /**
     * Notifies the seller that a new ascending bid was placed on their auction.
     *
     * <p>The Telegram leg of this is the one high-volume alert in the system, so it is the one
     * that is coalesced rather than sent per event: it is queued under
     * {@code PRICE:{auctionId}} with a cooldown as its initial delay, and further bids inside
     * that window rewrite the queued body instead of adding messages. A twenty-bid war
     * therefore reaches the seller as a single message carrying the current figure. The
     * cooldown tightens inside the endgame window, where a stale price is worth less — see
     * {@link com.auction.telegram.TelegramConfig#priceCooldownSecondsFor}.</p>
     *
     * <p>Privacy: neither the in-app line nor the push names the bidder. A seller has no more
     * claim on their bidders' identities mid-auction than a bidder has on their rivals'.</p>
     */
    public static void notifySellerNewBid(long auctionId, java.math.BigDecimal bidAmount) {
        safe(() -> {
            Integer sellerId = sellerOf(auctionId);
            if (sellerId == null) return;
            SellerBidSnapshot snapshot = sellerBidSnapshot(auctionId);
            String amount = bidAmount != null ? formatMoney(bidAmount) : "$?";
            // The push carries the price as it stands now, not the bid that triggered it:
            // by the time a coalesced message is delivered, bidAmount is history.
            java.math.BigDecimal current = snapshot.topBid != null ? snapshot.topBid : bidAmount;
            create(sellerId, "NEW_BID",
                    "New bid of " + amount + " on \"" + snapshot.title + "\".",
                    "/auction/" + auctionId,
                    "New bid on your auction",
                    "Someone placed a bid of " + amount + " on \"" + snapshot.title
                            + "\" on AuctionHub.",
                    TelegramAlerts.sellerPrice(auctionId, snapshot.title, current,
                            snapshot.bidCount,
                            TelegramConfig.priceCooldownSecondsFor(snapshot.dateEnd)));
        });
    }

    /**
     * Notifies the seller that their auction ended without a winner (can relist).
     *
     * <p>Deduplicated on {@code (sellerId, "AUCTION_ENDED", "/auction/{id}")} for the same
     * reason {@link #notifyAuctionLost} is: {@code AuctionFinalizer} is re-entered by the
     * 60-second sweep and by any page that lazily finalises the same listing. The link is
     * per-auction rather than the seller dashboard precisely so that check can distinguish
     * one concluded listing from the next.</p>
     */
    public static void notifyAuctionEndedUnsold(long auctionId) {
        safe(() -> {
            Integer sellerId = sellerOf(auctionId);
            if (sellerId == null) return;
            String link = "/auction/" + auctionId;
            if (notificationDAO.exists(sellerId, "AUCTION_ENDED", link)) return;
            String title = auctionTitle(auctionId);
            // A price alert still waiting out its cooldown would arrive after this one and
            // describe the listing as live.
            TelegramNotifier.cancelPriceAlerts(auctionId);
            create(sellerId, "AUCTION_ENDED",
                    "Your auction \"" + title + "\" ended without a sale. You can relist it from your seller dashboard.",
                    link,
                    "Auction ended unsold",
                    "Your auction \"" + title + "\" ended without a winner on AuctionHub. You can relist it from your seller dashboard.",
                    TelegramAlerts.sellerUnsold(auctionId, title));
        });
    }

    /**
     * Notifies the winner that they won, and the seller that their item sold.
     *
     * <p>The seller's half carries the two facts it previously omitted — what the item made
     * and who bought it — because a bare "your item has sold" makes the seller open the
     * dashboard to learn anything at all. The buyer is identified only by a masked handle
     * (e.g. {@code c***e}), enough to recognise the person they are now in an order with;
     * the full identity stays on the order page behind authentication. Masking is applied
     * inside {@link TelegramAlerts#sellerSold} so no call site can skip it.</p>
     *
     * <p>Deduplicated on {@code (sellerId, "SOLD", "/auction/{id}")}, so that of the four
     * ways an auction can conclude — expiry, a declared winner, Buy It Now, Dutch acceptance
     * — plus lazy re-entry from the finalizer, only the first announces the result. The
     * Telegram leg carries {@code RESULT:{auctionId}} into the outbox's own dedupe index, so
     * a re-entry that races the first still cannot double-send.</p>
     */
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
            if (sellerId == null) return;
            String sellerLink = "/auction/" + auctionId;
            if (notificationDAO.exists(sellerId, "SOLD", sellerLink)) return;
            String winnerName = usernameOf(winnerId);
            String buyer = maskLabel(winnerName);
            String price = summary.topBid != null ? formatMoney(summary.topBid) : null;
            // The listing is closed, so a price alert still inside its cooldown is now wrong.
            TelegramNotifier.cancelPriceAlerts(auctionId);
            create(sellerId, "SOLD",
                    "Your item \"" + title + "\" sold"
                            + (price != null ? " for " + price : "")
                            + " to " + buyer + ". Arrange delivery from your seller dashboard.",
                    sellerLink,
                    "Your item sold",
                    "Your auction \"" + title + "\" has a winner on AuctionHub"
                            + (price != null ? ", at " + price : "")
                            + ". The buyer's full details are on the order in your seller dashboard.",
                    TelegramAlerts.sellerSold(auctionId, title, summary.topBid, winnerName));
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
     * Tells everyone who bid on an auction that the seller has withdrawn it.
     *
     * <p>Nothing announced a cancellation before this, in any channel: a buyer who had bid on
     * a listing the seller then pulled simply found it gone. That is the worse half of minimum
     * requirement Seller (d) — the platform could cancel, but the people affected were never
     * told. Reaching the same recipients as {@link #notifyAuctionLost} (every distinct bidder,
     * once each however many times they bid) and deduplicated on
     * {@code (userId, "AUCTION_CANCELLED", link)} for the same reason: a seller who removes the
     * final unit of a listing reaches this through {@code reduce-quantity} rather than
     * {@code cancel}, and neither path should be able to message anyone twice.</p>
     *
     * <p>The seller's own reason is passed through to the recipients. It is the only thing that
     * distinguishes "I have no bids and want out" from "the item is damaged", the bidder is the
     * person entitled to know which, and it is now a required field on the cancel form — so
     * unlike the refund reason (which stays on the order page) it belongs in the message. It is
     * sanitised at the servlet boundary before reaching here.</p>
     *
     * <p>Not preference-gated beyond the master switches, matching {@code LOST}: this is a
     * material change to something the recipient has money committed to, not marketing.</p>
     *
     * @param reason the seller's stated reason; a blank or null value is simply omitted
     */
    public static void notifyAuctionCancelled(long auctionId, String reason) {
        safe(() -> {
            String title = auctionTitle(auctionId);
            String link = "/auction/" + auctionId;
            String because = (reason == null || reason.isBlank()) ? "" : " Reason given: " + reason.trim();
            // The listing is closed, so a seller price alert still inside its cooldown is now
            // wrong in the same way it would be after a sale.
            TelegramNotifier.cancelPriceAlerts(auctionId);

            for (int bidderId : distinctBidders(auctionId)) {
                if (notificationDAO.exists(bidderId, "AUCTION_CANCELLED", link)) continue;
                create(bidderId, "AUCTION_CANCELLED",
                        "The seller cancelled \"" + title + "\", so your bid no longer stands." + because,
                        link,
                        "An auction you bid on was cancelled",
                        "The seller has cancelled \"" + title + "\" on AuctionHub, so your bid no longer "
                                + "stands and nothing is owed." + because
                                + " Browse similar listings to keep looking.",
                        TelegramAlerts.auctionCancelled(auctionId, bidderId, title));
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

    // ── Order lifecycle ───────────────────────────────────────────────────────
    //
    // These take an order id and nothing else, because everything they need — both parties,
    // the item, the amount — hangs off the order and was previously being fetched piecemeal
    // by the servlets to pass back in. It also gives the Telegram leg the one identifier its
    // dedupe keys are built on, so a stage cannot be announced twice.

    /** Notifies the seller that the winning buyer has paid. */
    public static void notifyOrderPaid(long orderId) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            create(o.sellerId, "ORDER_PAID",
                    "Payment received for \"" + o.title + "\". You can now arrange delivery.",
                    "/sales",
                    "Payment received",
                    "The winning buyer paid for \"" + o.title + "\" on AuctionHub.",
                    TelegramAlerts.orderPaidToSeller(orderId, o.auctionId, o.title, o.amount,
                            o.buyerUsername));
        });
    }

    /**
     * Sends the buyer a payment confirmation and itemised receipt after a successful order
     * payment. {@code paymentLabel} describes the method used (e.g. "Visa ****4242",
     * "PayPal (a@b.com)"); pass null when unknown.
     *
     * <p>The method label goes in the in-app line and the emailed receipt, which is where a
     * proof-of-payment record belongs, but deliberately not into the push: a card hint on a
     * lock screen buys the reader nothing they did not just do themselves.</p>
     */
    public static void notifyBuyerPaymentReceipt(long orderId, String paymentLabel) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            String amount = o.amount != null ? formatMoney(o.amount) : "";
            String method = (paymentLabel == null || paymentLabel.isBlank()) ? "your payment method" : paymentLabel;

            String inApp = "Payment confirmed for \"" + o.title + "\""
                    + (amount.isEmpty() ? "" : " (" + amount + ")") + ". A receipt has been emailed to you.";

            StringBuilder body = new StringBuilder();
            body.append("Thank you for your payment on AuctionHub.\n\n");
            body.append("Payment confirmation / receipt\n");
            body.append("------------------------------\n");
            body.append("Order #: ").append(orderId).append('\n');
            body.append("Item: ").append(o.title).append('\n');
            if (!amount.isEmpty()) body.append("Amount paid: ").append(amount).append('\n');
            body.append("Payment method: ").append(method).append('\n');
            body.append("Status: PAID\n\n");
            body.append("Keep this email as proof of your transaction. "
                    + "You can track delivery and confirm receipt from your Orders page.");

            create(o.buyerId, "PAYMENT_RECEIPT", inApp, "/purchases",
                    "Your AuctionHub payment receipt (Order #" + orderId + ")", body.toString(),
                    TelegramAlerts.orderPaymentConfirmed(orderId, o.auctionId, o.title, o.amount));
        });
    }

    /**
     * Tells the buyer their order moved to {@code shippingStatus}.
     *
     * <p>Until now only the last of these steps said anything: a seller could mark an order
     * shipped and then out for delivery and the buyer, who is the one person waiting for it,
     * would learn nothing in any channel. So this covers the whole
     * {@code SHIPPED → IN_TRANSIT → DELIVERED} tail.</p>
     *
     * <p>{@code PREPARING} is intentionally silent. It is set by the payment itself rather
     * than by any decision of the seller's, so announcing it would mean two messages for one
     * event, and the payment confirmation already says the seller is getting the item ready.</p>
     */
    public static void notifyOrderShippingAdvanced(long orderId, String shippingStatus) {
        safe(() -> {
            if (shippingStatus == null) return;
            switch (shippingStatus.toUpperCase()) {
                case "SHIPPED":
                    notifyBuyerShippingStage(orderId, "ORDER_SHIPPED",
                            " has been shipped. Track it from My purchases.",
                            "Your order has shipped",
                            " has been handed over for delivery.", true);
                    break;
                case "IN_TRANSIT":
                    notifyBuyerShippingStage(orderId, "ORDER_IN_TRANSIT",
                            " is out for delivery and should arrive shortly.",
                            "Your order is out for delivery",
                            " is out for delivery and should reach you shortly.", false);
                    break;
                case "DELIVERED":
                    notifyOrderDelivered(orderId);
                    break;
                default:
                    break;
            }
        });
    }

    /** The two mid-flight shipping steps, which differ only in their wording. */
    private static void notifyBuyerShippingStage(long orderId, String type, String inAppTail,
                                                 String emailSubject, String emailTail,
                                                 boolean shipped) {
        OrderSummary o = orderSummary(orderId);
        if (o == null) return;
        create(o.buyerId, type,
                "Your order \"" + o.title + "\"" + inAppTail,
                "/purchases",
                emailSubject,
                "Your AuctionHub order \"" + o.title + "\"" + emailTail,
                shipped ? TelegramAlerts.orderShipped(orderId, o.auctionId, o.title)
                        : TelegramAlerts.orderOutForDelivery(orderId, o.auctionId, o.title));
    }

    /** Notifies the buyer that the package was marked delivered — confirm receipt when ready. */
    public static void notifyOrderDelivered(long orderId) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            create(o.buyerId, "ORDER_DELIVERED",
                    "Your order \"" + o.title + "\" was marked delivered. Please confirm receipt in Orders.",
                    "/purchases",
                    "Package delivered",
                    "Your order \"" + o.title + "\" was marked delivered on AuctionHub. Confirm receipt when you receive it.",
                    TelegramAlerts.orderDelivered(orderId, o.auctionId, o.title));
        });
    }

    /** Notifies the seller that the buyer confirmed receipt; payment/earnings are complete. */
    public static void notifySellerReceiptConfirmed(long orderId) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            create(o.sellerId, "ORDER_COMPLETED",
                    "The buyer confirmed receipt for \"" + o.title
                            + "\". Payment is complete and funds are reflected in your earnings summary.",
                    "/sales",
                    "Payment complete — receipt confirmed",
                    "The buyer confirmed receipt for \"" + o.title
                            + "\" on AuctionHub. Payment is complete and the sale is reflected in your earnings.",
                    TelegramAlerts.orderCompleted(orderId, o.auctionId, o.title, o.amount,
                            o.buyerUsername));
        });
    }

    /**
     * Notifies the seller that the buyer requested a refund.
     *
     * <p>The buyer's reason reaches the seller through the order page, not the alert: it is
     * free text written in a dispute, and the seller has to open the order to answer it.</p>
     */
    public static void notifySellerRefundRequested(long orderId) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            create(o.sellerId, "REFUND_REQUESTED",
                    "The buyer requested a refund for \"" + o.title + "\". Review it in your Orders.",
                    "/sales",
                    "Refund requested",
                    "The buyer requested a refund for \"" + o.title + "\" on AuctionHub.",
                    TelegramAlerts.refundRequested(orderId, o.auctionId, o.title, o.buyerUsername));
        });
    }

    /** Notifies the buyer that the seller approved or declined their refund request. */
    public static void notifyBuyerRefundResolved(long orderId, boolean approved) {
        notifyBuyerRefundResolved(orderId, approved, "The seller");
    }

    /** Same as above, but names a different decision maker (e.g. "An AuctionHub admin"). */
    public static void notifyBuyerRefundResolved(long orderId, boolean approved, String actor) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            String verb = approved ? "approved" : "declined";
            create(o.buyerId, approved ? "REFUND_APPROVED" : "REFUND_REJECTED",
                    actor + " " + verb + " your refund request for \"" + o.title + "\".",
                    "/purchases",
                    "Refund " + verb,
                    actor + " " + verb + " your refund request for \"" + o.title + "\" on AuctionHub.",
                    TelegramAlerts.refundResolved(orderId, o.auctionId, o.title, o.amount, approved));
        });
    }

    /**
     * Notifies the non-paying buyer that their unpaid order was automatically cancelled
     * (payment deadline missed). Companion to {@link #notifyOrderPaymentTimeoutSeller}; the
     * two are always fired together by the scheduled auto-cancel sweep.
     */
    public static void notifyOrderPaymentTimeoutBuyer(long orderId) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            create(o.buyerId, "ORDER_CANCELLED",
                    "Your order for \"" + o.title + "\" was cancelled because payment was not received in time.",
                    "/purchases",
                    "Order cancelled — payment deadline passed",
                    "Your order for \"" + o.title + "\" on AuctionHub was automatically cancelled because "
                            + "payment was not made within the required window.",
                    TelegramAlerts.orderPaymentTimeoutBuyer(orderId, o.auctionId, o.title));
        });
    }

    /**
     * Notifies the seller that the winning buyer never paid, so the order was cancelled and
     * the listing closed unsold (see {@code OrderDAO#cancelOverduePendingOrders} for the
     * "unsold rather than re-award" decision).
     */
    public static void notifyOrderPaymentTimeoutSeller(long orderId) {
        safe(() -> {
            OrderSummary o = orderSummary(orderId);
            if (o == null) return;
            create(o.sellerId, "ORDER_CANCELLED_SELLER",
                    "The winning bidder for \"" + o.title + "\" did not pay in time. "
                            + "The order was cancelled and the listing closed unsold.",
                    "/sales",
                    "Buyer did not pay — order cancelled",
                    "The winning bidder for \"" + o.title + "\" on AuctionHub did not pay within the required "
                            + "window. The order was cancelled and the listing closed unsold. "
                            + "You can relist the item from your seller dashboard.",
                    TelegramAlerts.orderPaymentTimeoutSeller(orderId, o.auctionId, o.title));
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
     *                 that have no push equivalent (admin alerts, Q&amp;A replies, order chatter)
     */
    private static void create(int userId, String type, String message, String link,
                               String emailSubject, String emailBody,
                               TelegramAlerts.Alert telegram) {
        if (!allowedByPreference(userId, type)) return;
        notificationDAO.create(userId, type, message, link);
        if (MailConfig.isSmtpConfigured()) {
            MAIL_EXECUTOR.execute(() -> {
                try {
                    User u = userDAO.getUserById(userId);
                    if (u != null && u.getEmail() != null && !u.getEmail().isBlank()) {
                        OtpMailer.sendNotification(u.getEmail(), emailSubject, emailBody);
                    }
                } catch (Exception e) {
                    LOG.fine("Notification email skipped: " + e.getMessage());
                }
            });
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

    /**
     * What the seller's live price feed needs: the figure, how contested the listing is, and
     * when it closes — the last of which decides whether the endgame cooldown applies.
     */
    private static final class SellerBidSnapshot {
        final String title;
        final java.math.BigDecimal topBid;
        final int bidCount;
        final java.time.Instant dateEnd;

        SellerBidSnapshot(String title, java.math.BigDecimal topBid, int bidCount,
                          java.time.Instant dateEnd) {
            this.title = title;
            this.topBid = topBid;
            this.bidCount = bidCount;
            this.dateEnd = dateEnd;
        }
    }

    /** One round trip for the four facts, since this runs on every bid. */
    private static SellerBidSnapshot sellerBidSnapshot(long auctionId) {
        String sql = "SELECT d.title, a.date_end, "
                + "(SELECT COUNT(*) FROM bids WHERE auction_id = a.auction_id) AS bid_count, "
                + "(SELECT MAX(bid_amount) FROM bids WHERE auction_id = a.auction_id) AS top_bid "
                + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
                + "WHERE a.auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String title = rs.getString("title");
                    java.sql.Timestamp end = rs.getTimestamp("date_end");
                    return new SellerBidSnapshot(
                            (title == null || title.isBlank()) ? "your item" : title,
                            rs.getBigDecimal("top_bid"),
                            rs.getInt("bid_count"),
                            end == null ? null : end.toInstant());
                }
            }
        } catch (Exception e) {
            LOG.fine("sellerBidSnapshot lookup failed: " + e.getMessage());
        }
        return new SellerBidSnapshot("your item", null, 0, null);
    }

    /**
     * The account's display name, or {@code null} when it cannot be resolved. One column
     * rather than {@code UserDAO.getUserById}, which reads the whole profile: the only thing
     * wanted here is a name that is about to be masked down to two characters anyway.
     */
    private static String usernameOf(int userId) {
        String sql = "SELECT username FROM users WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            LOG.fine("username lookup failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * A partly-hidden display name for the counterparty, e.g. {@code c***e}. Used in the
     * seller's in-app result line as well as the push: the notification bell is no more the
     * right place for a full identity than a phone lock screen is, and the unmasked name is
     * one click away on the order.
     */
    private static String maskLabel(String username) {
        String masked = com.auction.util.SecurityUtil.maskUsername(username);
        return (masked == null || masked.isBlank()) ? "a verified buyer" : masked;
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

    /**
     * Everything an order-stage notification needs: who the two parties are, what the item
     * was and what it cost. The buyer's raw username is included only so the builders can
     * mask it — nothing here writes it out.
     */
    private static final class OrderSummary {
        final long auctionId;
        final int buyerId;
        final int sellerId;
        final java.math.BigDecimal amount;
        final String title;
        final String buyerUsername;

        OrderSummary(long auctionId, int buyerId, int sellerId, java.math.BigDecimal amount,
                     String title, String buyerUsername) {
            this.auctionId = auctionId;
            this.buyerId = buyerId;
            this.sellerId = sellerId;
            this.amount = amount;
            this.title = (title == null || title.isBlank()) ? "your order" : title;
            this.buyerUsername = buyerUsername;
        }
    }

    /** One round trip, since every order-stage notification needs the same six facts. */
    private static OrderSummary orderSummary(long orderId) {
        String sql = "SELECT o.auction_id, o.buyer_id, o.seller_id, o.amount, d.title, "
                + "b.username AS buyer_username "
                + "FROM orders o "
                + "JOIN auction_details d ON d.id = o.auction_id "
                + "LEFT JOIN users b ON b.id = o.buyer_id "
                + "WHERE o.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new OrderSummary(
                            rs.getLong("auction_id"),
                            rs.getInt("buyer_id"),
                            rs.getInt("seller_id"),
                            rs.getBigDecimal("amount"),
                            rs.getString("title"),
                            rs.getString("buyer_username"));
                }
            }
        } catch (Exception e) {
            LOG.fine("orderSummary lookup failed: " + e.getMessage());
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
     * Every distinct bidder on the auction. The no-winner counterpart to
     * {@link #losingBidders}: a cancelled auction has no winner to exclude, so everyone who
     * bid is a recipient, once each however many bids they placed.
     */
    private static List<Integer> distinctBidders(long auctionId) {
        String sql = "SELECT DISTINCT user_id FROM bids WHERE auction_id = ?";
        List<Integer> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (Exception e) {
            LOG.fine("distinctBidders lookup failed: " + e.getMessage());
        }
        return out;
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

    /**
     * Wraps every public hook so a notification failure is logged and dropped rather than
     * propagating. The caller is always in the middle of the user's real action, and a bid
     * that succeeded must not be reported as failed because the follow-up message could not
     * be built.
     */
    private static void safe(Runnable r) {
        try { r.run(); } catch (Exception e) { LOG.warning("Notification failed: " + e.getMessage()); }
    }
}
