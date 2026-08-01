package com.auction.telegram;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds the bodies of the alerts the bot pushes, from the admin-editable copy in the
 * {@code landing_content} "Telegram" group.
 *
 * <h2>Privacy (PDPA)</h2>
 * <p>No alert may name the other party. An opponent's identity is personal data, and in a
 * live marketplace it is also strategically useful — knowing <em>who</em> outbid you tells
 * you how much further they are likely to go. So {@link #outbid} says only that a higher
 * bid exists, and {@link #lost} says only what the item sold for. Neither takes a user id
 * or a username as a parameter, which is what makes that guarantee checkable rather than
 * a matter of remembering.</p>
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

        Alert(String eventType, String body, Long auctionId, String dedupeKey) {
            this.eventType = eventType;
            this.body = body;
            this.auctionId = auctionId;
            this.dedupeKey = dedupeKey;
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

    private static Alert build(String eventType, String copyKey, String fallback,
                               long auctionId, int userId, String title, BigDecimal price) {
        String template = TelegramCopy.get(copyKey, fallback);
        String body = render(template, title, price) + link(auctionId);
        return new Alert(eventType, body, auctionId, eventType + ":" + auctionId + ":" + userId);
    }

    /**
     * Substitutes the two placeholders the copy contract defines. The title is escaped and
     * then emboldened here rather than in the copy, so an administrator rewording a message
     * cannot accidentally emit broken markup — or inject any.
     */
    static String render(String template, String title, BigDecimal price) {
        String safeTitle = "<b>" + TelegramClient.escapeHtml(blankToFallback(title)) + "</b>";
        String safePrice = TelegramClient.escapeHtml(formatMoney(price));
        return template.replace("{title}", safeTitle).replace("{price}", safePrice);
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
