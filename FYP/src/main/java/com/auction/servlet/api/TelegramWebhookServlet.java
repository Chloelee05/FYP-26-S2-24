package com.auction.servlet.api;

import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.telegram.TelegramAttemptLimiter;
import com.auction.telegram.TelegramClient;
import com.auction.telegram.TelegramConfig;
import com.auction.telegram.TelegramCopy;
import com.auction.util.SecurityUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * {@code POST /api/telegram/webhook} — the bot's inbound endpoint.
 *
 * <p>Not session-authenticated: the caller is Telegram, not a browser. Authentication is
 * the {@code X-Telegram-Bot-Api-Secret-Token} header that Telegram echoes from
 * {@code setWebhook}, compared in constant time before the body is read at all. A
 * mismatch gets a bare 401 with nothing parsed and nothing logged that could confirm a
 * partial guess. {@code AuthFilter} only guards {@code /protected/*}, so no filter
 * exclusion is needed.</p>
 *
 * <p>Successful handling always answers 200, even when the update is meaningless to us,
 * because a non-2xx makes Telegram redeliver. Redelivery is safe anyway: the code
 * consumption is a single conditional UPDATE, so a replayed {@code /start} links once and
 * then reports an invalid code rather than linking twice.</p>
 */
@WebServlet("/api/telegram/webhook")
public class TelegramWebhookServlet extends ApiBase {

    private static final Logger LOG = Logger.getLogger(TelegramWebhookServlet.class.getName());

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private TelegramLinkDAO linkDAO = new TelegramLinkDAO();
    private UserDAO userDAO = new UserDAO();
    private TelegramAttemptLimiter limiter = TelegramAttemptLimiter.getInstance();

    /** Test hooks. The limiter is a process-wide singleton, so tests swap in a fresh one. */
    public void setTelegramLinkDAO(TelegramLinkDAO dao) { this.linkDAO = dao; }
    public void setUserDAO(UserDAO dao) { this.userDAO = dao; }
    public void setAttemptLimiter(TelegramAttemptLimiter limiter) { this.limiter = limiter; }

    /**
     * Serves POST /api/telegram/webhook. The body is a Telegram Update object. Checks the shared
     * secret header, pulls the chat id and message text out of either {@code message} or
     * {@code edited_message}, and dispatches the command. Answers 200 for anything it can accept,
     * 401 for a bad secret and 503 when no bot is configured.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!TelegramConfig.isConfigured()) {
            // Nothing can be authenticated without a configured secret, so refuse rather
            // than accept unauthenticated traffic on a server with no bot.
            resp.setStatus(503);
            return;
        }
        if (!secretMatches(req.getHeader(SECRET_HEADER))) {
            resp.setStatus(401);
            return;
        }

        String chatId = null;
        try {
            JsonNode update = MAPPER.readTree(readBody(req));
            JsonNode message = update.path("message");
            if (message.isMissingNode() || message.isNull()) {
                message = update.path("edited_message");
            }
            chatId = message.path("chat").path("id").isMissingNode()
                    ? null
                    : message.path("chat").path("id").asText(null);
            if (chatId == null || chatId.isBlank()) {
                resp.setStatus(200);
                return;
            }
            String text = message.path("text").asText("").trim();
            String telegramUsername = message.path("from").path("username").asText(null);

            handleCommand(chatId, text, telegramUsername);
        } catch (Exception e) {
            // Never surface a 500: Telegram would retry a message we cannot process anyway.
            LOG.log(Level.WARNING, "Telegram webhook could not process an update: {0}",
                    e.getClass().getSimpleName());
        }
        resp.setStatus(200);
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    /**
     * Maps the message text to an action. {@code /start} with a payload carries the deep-link
     * token, a bare six-digit message is the manually typed code, and anything unrecognised gets
     * the help text rather than silence, so a confused user is not left with no reply.
     */
    private void handleCommand(String chatId, String text, String telegramUsername) {
        if (text.startsWith("/start")) {
            String payload = text.length() > "/start".length()
                    ? text.substring("/start".length()).trim()
                    : "";
            if (payload.isEmpty()) {
                reply(chatId, TelegramCopy.get("telegram.bot.welcome", DEFAULT_WELCOME));
            } else {
                attemptLink(chatId, payload, telegramUsername);
            }
            return;
        }
        if (text.startsWith("/unlink")) {
            handleUnlink(chatId);
            return;
        }
        if (text.startsWith("/status")) {
            handleStatus(chatId);
            return;
        }
        if (text.startsWith("/help")) {
            reply(chatId, TelegramCopy.get("telegram.bot.help", DEFAULT_HELP));
            return;
        }
        if (OTP_PATTERN.matcher(text).matches()) {
            attemptLink(chatId, text, telegramUsername);
            return;
        }
        reply(chatId, TelegramCopy.get("telegram.bot.help", DEFAULT_HELP));
    }

    /**
     * Redeems a deep-link token or a typed OTP — the same call either way, so the two
     * entry points cannot drift apart in what they accept or how they consume a code.
     */
    private void attemptLink(String chatId, String code, String telegramUsername) {
        // The limiter is keyed on a hash of the chat id, so a guessing loop from one chat is
        // throttled without the raw chat id being held in memory as the key.
        String chatKey = TelegramLinkDAO.hash(chatId);

        if (limiter.isBlocked(chatKey)) {
            long minutes = Math.max(1, limiter.blockedSecondsRemaining(chatKey) / 60);
            reply(chatId, "Too many incorrect codes. Try again in about " + minutes + " minute(s).");
            return;
        }

        Integer userId = linkDAO.consumeCode(code);
        if (userId == null) {
            limiter.recordFailure(chatKey);
            reply(chatId, TelegramCopy.get("telegram.bot.invalidCode", DEFAULT_INVALID_CODE));
            return;
        }
        limiter.recordSuccess(chatKey);

        TelegramLinkDAO.LinkOutcome outcome = linkDAO.link(userId, chatId, telegramUsername);

        // Tell the chat that just lost the link. An attacker who moved someone's alerts to
        // their own device is invisible otherwise, so this is the victim's only signal.
        if (outcome.displacedChatIdEncrypted != null) {
            notifyDisplacedChat(outcome.displacedChatIdEncrypted);
        }

        reply(chatId, TelegramCopy.get("telegram.bot.linked", DEFAULT_LINKED)
                + "\n\n" + accountLine(userId));
    }

    /** Handles /unlink from inside the chat, so a user can stop alerts without signing in to the site. */
    private void handleUnlink(String chatId) {
        boolean removed = linkDAO.unlinkChat(chatId);
        reply(chatId, removed
                ? TelegramCopy.get("telegram.bot.unlinked", DEFAULT_UNLINKED)
                : "This Telegram account isn't linked to AuctionHub, so there is nothing to disconnect.");
    }

    /** Handles /status: says whether this chat is linked and, if so, which account it is linked to, masked. */
    private void handleStatus(String chatId) {
        Integer userId = linkDAO.findUserIdByChatId(chatId);
        if (userId == null) {
            reply(chatId, "This Telegram account isn't linked to AuctionHub yet. "
                    + "Open Account settings on AuctionHub to connect it.");
            return;
        }
        reply(chatId, "Connected. " + accountLine(userId));
    }

    /**
     * Identifies the account without exposing it: the display name is masked and the email
     * is never sent, so a linked chat in the wrong hands still reveals nothing usable.
     */
    private String accountLine(int userId) {
        try {
            User user = userDAO.getUserById(userId);
            String masked = user == null ? null : SecurityUtil.maskUsername(user.getUsername());
            if (masked != null && !masked.isBlank()) {
                return "Account: <b>" + TelegramClient.escapeHtml(masked) + "</b>";
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not resolve account name for Telegram reply", e);
        }
        return "Account: <b>linked</b>";
    }

    /**
     * Warns the chat that used to hold the link. Chat ids are stored encrypted, so the old one
     * has to be decrypted before it can be messaged. Failures are logged quietly, since this is
     * a courtesy on top of a link that has already succeeded.
     */
    private void notifyDisplacedChat(String encryptedChatId) {
        try {
            String oldChatId = SecurityUtil.decrypt(encryptedChatId);
            if (oldChatId != null && !oldChatId.isBlank()) {
                reply(oldChatId, TelegramCopy.get("telegram.bot.moved", DEFAULT_MOVED));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not notify the previous Telegram chat", e);
        }
    }

    /** Sends one HTML-formatted message back to the chat through the bot API. */
    private void reply(String chatId, String html) {
        TelegramClient.sendMessage(chatId, html);
    }

    // -------------------------------------------------------------------------
    // Request plumbing
    // -------------------------------------------------------------------------

    /**
     * Constant-time comparison, so response timing cannot be used to learn the secret one
     * character at a time.
     */
    private static boolean secretMatches(String presented) {
        String expected = TelegramConfig.webhookSecret();
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the request body as text, stopping at {@link #MAX_BODY_BYTES} so a huge or endless
     * body cannot exhaust memory on an endpoint that anyone on the internet can reach.
     */
    private static String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        try (java.io.BufferedReader reader = req.getReader()) {
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
                if (sb.length() > MAX_BODY_BYTES) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    // Fallbacks used when landing_content has not been seeded yet.
    private static final String DEFAULT_WELCOME =
            "Hello! This bot delivers AuctionHub auction alerts.\n\n"
            + "To connect, open Account settings on AuctionHub, choose Connect Telegram, "
            + "and either tap the link there or send me the 6-digit code it shows you.";
    private static final String DEFAULT_LINKED =
            "You're connected. I'll message you here when you are outbid, when you win, "
            + "and when your listings close.";
    private static final String DEFAULT_INVALID_CODE =
            "That code isn't valid — it may have expired or already been used. "
            + "Open Account settings on AuctionHub and start the connection again.";
    private static final String DEFAULT_UNLINKED =
            "This Telegram account is no longer linked to AuctionHub and will not receive further alerts.";
    private static final String DEFAULT_MOVED =
            "Heads up: this AuctionHub account has just been connected to a different Telegram account, "
            + "so alerts will no longer arrive here. If that wasn't you, sign in to AuctionHub "
            + "and change your password.";
    private static final String DEFAULT_HELP =
            "Commands I understand:\n"
            + "/status — show whether this chat is linked\n"
            + "/unlink — stop receiving AuctionHub alerts here\n"
            + "/help — show this message\n\n"
            + "You can also just send me the 6-digit code from AuctionHub to connect.";
}
