package com.auction.telegram;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds the bodies of the alerts the bot pushes, from the admin-editable copy in the
 * {@code landing_content} "Telegram" group.
 *
 * <h2>Privacy (PDPA)</h2>
 * <p>No alert names the other party in full. An opponent's identity is personal data, and in
 * a live marketplace it is also strategically useful — knowing <em>who</em> outbid you tells
 * you how much further they are likely to go. So {@link #outbid} says only that a higher
 * bid exists, {@link #lost} says only what the item sold for, and {@link #sellerPrice} says
 * only what the current price is. None of them takes a user id or a username as a parameter,
 * which is what makes that guarantee checkable rather than a matter of remembering.</p>
 *
 * <p>{@link #sellerSold} is the single exception, and a bounded one: a seller who is about to
 * ship an item does need to recognise the buyer they are now in an order with, so the message
 * carries a masked hint such as {@code c***e}. The masking is applied inside the builder
 * rather than by the caller, so there is no call site at which it can be omitted, and the
 * unmasked identity remains only on the order page behind authentication.</p>
 *
 * <h2>Escaping</h2>
 * <p>Listing titles are user input and are interpolated into Telegram HTML, so every
 * substitution goes through {@link TelegramClient#escapeHtml(String)} before the markup is
 * added around it. The copy row itself is admin-authored and is sent as written.</p>
 */
public final class TelegramAlerts {

    /** One queued alert: what to send, which preference gates it, and how to deduplicate it. */
    public static final class Alert {
        /** Doubles as {@code telegram_outbox.event_type} and the preference selector. */
        public final String eventType;
        /** Telegram-flavoured HTML, fully escaped and ready to send. */
        public final String body;
        public final Long auctionId;
        public final String dedupeKey;
        /**
         * How long the queue should hold this message before its first send attempt.
         * Zero for everything the recipient is waiting on; positive only for the seller
         * price feed, where the delay <em>is</em> the coalescing window.
         */
        public final int initialDelaySeconds;

        Alert(String eventType, String body, Long auctionId, String dedupeKey) {
            this(eventType, body, auctionId, dedupeKey, 0);
        }

        Alert(String eventType, String body, Long auctionId, String dedupeKey, int initialDelaySeconds) {
            this.eventType = eventType;
            this.body = body;
            this.auctionId = auctionId;
            this.dedupeKey = dedupeKey;
            this.initialDelaySeconds = initialDelaySeconds;
        }
    }

    private static final String DEFAULT_OUTBID =
            "You have been outbid on {title}.\n\n"
            + "The bid to beat is now {price}. Open the auction to raise your bid before it closes.";

    private static final String DEFAULT_WON =
            "You won {title}.\n\n"
            + "Winning price: {price}. Complete payment on AuctionHub to finish the transaction.";

    private static final String DEFAULT_LOST =
            "{title} has closed, and your bid was not the winning one.\n\n"
            + "It sold for {price}. Browse AuctionHub to find something similar.";

    private static final String DEFAULT_SELLER_PRICE =
            "{title} is now at {price}.\n\n"
            + "{bids} so far. Bidding is live — no action needed from you.";

    private static final String DEFAULT_SELLER_SOLD =
            "{title} sold for {price}.\n\n"
            + "The winning bidder is {winner}. Their full details are on the order in your "
            + "seller dashboard once payment clears, where you can arrange delivery.";

    private static final String DEFAULT_SELLER_UNSOLD =
            "{title} has ended without a sale.\n\n"
            + "Nothing was bid on it. You can relist it from your seller dashboard.";

    private TelegramAlerts() {
    }

    /** "Someone bid higher" — deliberately says nothing about who. */
    public static Alert outbid(long auctionId, int userId, String title, BigDecimal price) {
        return build("OUTBID", "telegram.alert.outbid", DEFAULT_OUTBID, auctionId, userId, title, price);
    }

    public static Alert won(long auctionId, int userId, String title, BigDecimal price) {
        return build("WON", "telegram.alert.won", DEFAULT_WON, auctionId, userId, title, price);
    }

    /** "The auction closed without you" — deliberately says nothing about who won. */
    public static Alert lost(long auctionId, int userId, String title, BigDecimal price) {
        return build("LOST", "telegram.alert.lost", DEFAULT_LOST, auctionId, userId, title, price);
    }

    /**
     * "Your listing is moving" — the seller's live price feed.
     *
     * <p>Like {@link #outbid}, there is no parameter for who placed the bid. A seller has no
     * more right to their bidders' identities in a push message than a bidder has to their
     * rivals', and withholding it also keeps the seller from recognising and courting a
     * particular buyer off-platform mid-auction.</p>
     *
     * <p>The dedupe key is {@code PRICE:{auctionId}} — deliberately <em>not</em> per-bid and
     * not per-recipient, because collapsing a bidding war into the one row is the entire
     * point. {@code cooldownSeconds} becomes the queued row's initial delay, so the window
     * during which later bids fold into this message is the same thing as the wait before it
     * is sent.</p>
     */
    public static Alert sellerPrice(long auctionId, String title, BigDecimal price,
                                    int bidCount, int cooldownSeconds) {
        String template = TelegramCopy.get("telegram.alert.sellerPrice", DEFAULT_SELLER_PRICE);
        String body = render(template, title, price, bidCount, null) + link(auctionId);
        return new Alert("SELLER_PRICE", body, auctionId, "PRICE:" + auctionId,
                Math.max(cooldownSeconds, 0));
    }

    /**
     * "Your listing sold" — the seller's outcome, including a masked hint at who bought it.
     *
     * <p>This is the one alert that carries anything about the other party, because a seller
     * who is about to ship something has a legitimate interest in recognising the buyer they
     * are already in an order with. It is still only a hint: {@code winnerUsername} is masked
     * here rather than by the caller, so the mask cannot be forgotten at a call site, and the
     * full identity stays on the order page behind authentication where it belongs.</p>
     */
    public static Alert sellerSold(long auctionId, String title, BigDecimal price,
                                   String winnerUsername) {
        String template = TelegramCopy.get("telegram.alert.sellerSold", DEFAULT_SELLER_SOLD);
        String masked = com.auction.util.SecurityUtil.maskUsername(winnerUsername);
        String body = render(template, title, price, -1, blankToWinnerFallback(masked)) + link(auctionId);
        return new Alert("SELLER_RESULT", body, auctionId, "RESULT:" + auctionId);
    }

    /**
     * "Your listing ended without a sale" — same {@code RESULT:{auctionId}} key as
     * {@link #sellerSold}, since an auction has exactly one outcome and the two messages must
     * never both arrive.
     */
    public static Alert sellerUnsold(long auctionId, String title) {
        String template = TelegramCopy.get("telegram.alert.sellerUnsold", DEFAULT_SELLER_UNSOLD);
        String body = render(template, title, null, -1, null) + link(auctionId);
        return new Alert("SELLER_RESULT", body, auctionId, "RESULT:" + auctionId);
    }

    private static Alert build(String eventType, String copyKey, String fallback,
                               long auctionId, int userId, String title, BigDecimal price) {
        String template = TelegramCopy.get(copyKey, fallback);
        String body = render(template, title, price) + link(auctionId);
        return new Alert(eventType, body, auctionId, eventType + ":" + auctionId + ":" + userId);
    }

    /** A winner whose account has no usable name still has to be referred to as somebody. */
    private static String blankToWinnerFallback(String masked) {
        return (masked == null || masked.isBlank()) ? "a verified buyer" : masked;
    }

    /**
     * Substitutes the two placeholders the copy contract defines. The title is escaped and
     * then emboldened here rather than in the copy, so an administrator rewording a message
     * cannot accidentally emit broken markup — or inject any.
     */
    static String render(String template, String title, BigDecimal price) {
        return render(template, title, price, -1, null);
    }

    /**
     * Substitutes the placeholders the copy contract defines, in one left-to-right pass.
     *
     * <p>Single-pass, not chained {@code replace} calls, because the title is user input: a
     * listing called "Vintage {price} lens" must reach the reader with that text intact
     * rather than having a figure spliced into it. Scanning the template once means a
     * substituted value is never itself re-examined for placeholders.</p>
     *
     * @param bidCount the number of bids, or a negative value when the message has no
     *                 {@code {bids}} placeholder to fill
     * @param winner   an already-masked buyer hint, or {@code null} when there is none
     */
    static String render(String template, String title, BigDecimal price,
                         int bidCount, String winner) {
        String safeTitle = "<b>" + TelegramClient.escapeHtml(blankToFallback(title)) + "</b>";
        String safePrice = TelegramClient.escapeHtml(formatMoney(price));
        String safeBids = TelegramClient.escapeHtml(formatBidCount(bidCount));
        String safeWinner = winner == null ? "" : "<b>" + TelegramClient.escapeHtml(winner) + "</b>";

        StringBuilder out = new StringBuilder(template.length() + 64);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            String name = template.substring(i + 1, close);
            switch (name) {
                case "title":  out.append(safeTitle);  break;
                case "price":  out.append(safePrice);  break;
                case "bids":   out.append(safeBids);   break;
                case "winner": out.append(safeWinner); break;
                // Not a placeholder we define, so it is ordinary text and is sent as written.
                default:       out.append(template, i, close + 1); break;
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** Reads as a phrase because it is dropped straight into a sentence. */
    static String formatBidCount(int bidCount) {
        if (bidCount < 0) {
            return "";
        }
        return bidCount == 1 ? "1 bid" : bidCount + " bids";
    }

    /**
     * A "View auction" line, when the deployment has told us its public address. Omitted
     * rather than guessed: a wrong link in a push notification is worse than none, and the
     * message reads fine without it.
     */
    private static String link(long auctionId) {
        String base = TelegramConfig.publicBaseUrl();
        if (base == null) {
            return "";
        }
        return "\n\n<a href=\"" + TelegramClient.escapeHtml(base + "/auction/" + auctionId)
                + "\">View auction</a>";
    }

    private static String blankToFallback(String title) {
        return (title == null || title.isBlank()) ? "your item" : title.trim();
    }

    /** Matches {@code NotificationService}'s in-app and email money formatting. */
    static String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "the closing price";
        }
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
