package com.auction.model.admin;

import com.auction.model.Role;

import java.time.Instant;

/**
 * A system-wide announcement composed by an admin — a maintenance window, a policy change,
 * or any other notice that has to reach the whole platform at once.
 *
 * <p><b>Delivery model.</b> An announcement is a master record, not a message queue. Sending it
 * fans out one row per targeted user into {@code notifications} with type
 * {@link #NOTIFICATION_TYPE}, which means announcements inherit everything the notification
 * feed already provides: the unread badge, per-user read state, the history list, and the
 * optional email copy. The {@code announcements} row is what survives afterwards, for audit —
 * who sent what, to which audience, and how many users it reached.</p>
 *
 * <p>Instances are immutable. A newly composed announcement (id 0, no timestamp, no recipients)
 * comes from {@link #compose}; the DAO turns it into a persisted snapshot with {@link #stored}.</p>
 *
 * <p>All input rules live here as pure functions so they can be unit-tested without a database
 * and so every entry point applies exactly the same limits.</p>
 */
public final class Announcement {

    /** The {@code notifications.type} value every broadcast row carries. */
    public static final String NOTIFICATION_TYPE = "ANNOUNCEMENT";

    /** Matches {@code announcements.title VARCHAR(150)}. */
    public static final int TITLE_MAX_LENGTH = 150;

    /** Long enough for a full maintenance notice; short enough to stay readable in the bell. */
    public static final int MESSAGE_MAX_LENGTH = 2000;

    /** Matches {@code announcements.link} and {@code notifications.link VARCHAR(512)}. */
    public static final int LINK_MAX_LENGTH = 512;

    /**
     * Who a broadcast reaches. Only active accounts are ever targeted: suspended, pending,
     * rejected and deleted users cannot sign in to read a notification.
     */
    public enum Audience {

        /** Every active account, whatever its role. */
        ALL(null),

        /** Active buyers only. */
        BUYERS(Role.BUYER),

        /** Active sellers only. */
        SELLERS(Role.SELLER);

        private final Role role;

        Audience(Role role) { this.role = role; }

        /** The role this audience narrows to, or {@code null} when it means everyone. */
        public Role role() { return role; }

        /** Human-readable form for confirmation messages, e.g. "all users", "buyers". */
        public String describe() { return this == ALL ? "all users" : name().toLowerCase(); }

        /** Case-insensitive lookup; {@code null} when {@code raw} names no audience. */
        public static Audience parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String trimmed = raw.trim();
            for (Audience a : values()) {
                if (a.name().equalsIgnoreCase(trimmed)) return a;
            }
            return null;
        }

        /** Comma-separated list of the accepted values, for error messages. */
        public static String allowedValues() { return "ALL, BUYERS, SELLERS"; }
    }

    /** How urgent the notice is. Drives the email subject prefix and the in-app styling. */
    public enum Severity {

        /** Routine notice: a new feature, a policy clarification. */
        INFO(null),

        /** Something users should plan around: a scheduled maintenance window. */
        WARNING("Important"),

        /** Immediate impact: an unplanned outage, an urgent policy enforcement. */
        CRITICAL("Urgent");

        private final String emailPrefix;

        Severity(String emailPrefix) { this.emailPrefix = emailPrefix; }

        /** Subject-line prefix for the email copy, or {@code null} for {@link #INFO}. */
        public String emailPrefix() { return emailPrefix; }

        /** Case-insensitive lookup; {@code null} when {@code raw} names no severity. */
        public static Severity parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String trimmed = raw.trim();
            for (Severity s : values()) {
                if (s.name().equalsIgnoreCase(trimmed)) return s;
            }
            return null;
        }

        /** Comma-separated list of the accepted values, for error messages. */
        public static String allowedValues() { return "INFO, WARNING, CRITICAL"; }
    }

    private final long id;
    private final String title;
    private final String message;
    private final Audience audience;
    private final Severity severity;
    private final String link;
    private final Integer createdBy;
    private final String createdByName;
    private final Instant createdAt;
    private final int recipientCount;

    public Announcement(long id, String title, String message, Audience audience, Severity severity,
                        String link, Integer createdBy, String createdByName,
                        Instant createdAt, int recipientCount) {
        this.id = id;
        this.title = normalize(title);
        this.message = normalize(message);
        this.audience = audience != null ? audience : Audience.ALL;
        this.severity = severity != null ? severity : Severity.INFO;
        this.link = normalize(link);
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.createdAt = createdAt;
        this.recipientCount = Math.max(0, recipientCount);
    }

    /**
     * A newly composed announcement, not yet persisted or delivered. Validate the text with
     * {@link #violationForTitle}, {@link #violationForMessage} and {@link #violationForLink}
     * before calling this.
     */
    public static Announcement compose(String title, String message, Audience audience,
                                       Severity severity, String link, Integer createdBy) {
        return new Announcement(0L, title, message, audience, severity, link, createdBy, null, null, 0);
    }

    /** The same announcement as persisted: with its generated id, timestamp and reach. */
    public Announcement stored(long storedId, Instant storedAt, int recipients) {
        return new Announcement(storedId, title, message, audience, severity, link,
                createdBy, createdByName, storedAt, recipients);
    }

    public long getId()             { return id; }
    public String getTitle()        { return title; }
    public String getMessage()      { return message; }
    public Audience getAudience()   { return audience; }
    public Severity getSeverity()   { return severity; }
    public String getLink()         { return link; }
    public Integer getCreatedBy()   { return createdBy; }
    public String getCreatedByName(){ return createdByName; }
    public Instant getCreatedAt()   { return createdAt; }
    public int getRecipientCount()  { return recipientCount; }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * The text written into each recipient's {@code notifications.message}. The notification
     * feed shows one line per item and has no title field of its own, so the title is folded
     * into the message.
     */
    public String toNotificationMessage() {
        return title + " — " + message;
    }

    /** Subject for the email copy; urgent notices say so before the title. */
    public String toEmailSubject() {
        String prefix = severity.emailPrefix();
        return prefix == null
                ? "AuctionHub announcement: " + title
                : "AuctionHub announcement (" + prefix + "): " + title;
    }

    /** Plain-text body for the email copy. */
    public String toEmailBody() {
        StringBuilder body = new StringBuilder();
        body.append(title).append("\n\n");
        body.append(message).append("\n\n");
        body.append("— The AuctionHub team\n");
        body.append("This is a system-wide announcement sent to ").append(audience.describe()).append('.');
        return body.toString();
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    /**
     * Normalizes admin-typed text: trims, converts CRLF to LF, and drops control characters
     * that would corrupt the notification feed or the email body. Tabs and newlines survive.
     *
     * <p>The text is deliberately <em>not</em> HTML-escaped here. It is stored raw through a
     * {@code PreparedStatement} and escaped where it is rendered — React escapes it in the
     * notification bell, and the email copy is plain text. Escaping on the way in would
     * double-escape and leave admins looking at {@code &amp;#x27;} in their own announcement.</p>
     *
     * @param raw text as typed, possibly {@code null}
     * @return the normalized text, or {@code null} when {@code raw} is {@code null} or blank
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) sb.append(c);
        }
        String normalized = sb.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Validates an admin-supplied announcement title.
     *
     * @return the violation message, or {@code null} when the value is acceptable
     */
    public static String violationForTitle(String title) {
        String value = normalize(title);
        if (value == null) {
            return "Announcement title is required.";
        }
        if (value.length() > TITLE_MAX_LENGTH) {
            return "Announcement title cannot exceed " + TITLE_MAX_LENGTH + " characters.";
        }
        if (value.indexOf('\n') >= 0) {
            return "Announcement title must be a single line.";
        }
        return null;
    }

    /**
     * Validates an admin-supplied announcement body.
     *
     * @return the violation message, or {@code null} when the value is acceptable
     */
    public static String violationForMessage(String message) {
        String value = normalize(message);
        if (value == null) {
            return "Announcement message is required.";
        }
        if (value.length() > MESSAGE_MAX_LENGTH) {
            return "Announcement message cannot exceed " + MESSAGE_MAX_LENGTH + " characters.";
        }
        return null;
    }

    /**
     * Validates the optional in-app link. Only internal paths are accepted: the notification
     * bell passes the link straight to the SPA router, which cannot leave the site, and
     * refusing absolute URLs keeps a broadcast from becoming an open redirect.
     *
     * @return the violation message, or {@code null} when the link is absent or acceptable
     */
    public static String violationForLink(String link) {
        String value = normalize(link);
        if (value == null) return null;
        if (value.length() > LINK_MAX_LENGTH) {
            return "Announcement link cannot exceed " + LINK_MAX_LENGTH + " characters.";
        }
        if (!value.startsWith("/") || value.startsWith("//")) {
            return "Announcement link must be an in-app path starting with \"/\", e.g. /profile.";
        }
        if (value.indexOf('\n') >= 0 || value.indexOf(' ') >= 0) {
            return "Announcement link cannot contain spaces or line breaks.";
        }
        return null;
    }
}
