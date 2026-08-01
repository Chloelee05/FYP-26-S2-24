package com.auction.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin Telegram Bot API client over the JDK 11 HTTP client, following the same
 * static-instance pattern as {@code OAuthApiServlet}.
 *
 * <p>Only {@code sendMessage} is needed: the webhook receives updates, it never polls.
 * Every call is fail-soft — a transport error or an API error becomes a
 * {@link SendResult}, never an exception — because the callers are a webhook that must
 * answer 200 quickly and (in phase 2) a background worker that decides on its own
 * whether to retry.</p>
 */
public final class TelegramClient {

    private static final Logger LOG = Logger.getLogger(TelegramClient.class.getName());

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Telegram rejects messages above 4096 characters outright. */
    private static final int MAX_MESSAGE_CHARS = 4096;

    private TelegramClient() {
    }

    /**
     * Outcome of one {@code sendMessage} call, in the terms the retry logic cares about.
     *
     * <p>{@code permanentFailure} marks the cases where retrying cannot help: the chat is
     * gone, the user blocked the bot, or the request itself is malformed. The phase-2
     * worker uses it to choose between backing off and giving up (and deactivating a link
     * that Telegram says no longer exists).</p>
     */
    public static final class SendResult {
        public final boolean ok;
        public final int errorCode;
        public final String description;
        /** Seconds Telegram asked us to wait (429 flood control), or 0. */
        public final int retryAfterSeconds;
        public final boolean permanentFailure;

        private SendResult(boolean ok, int errorCode, String description,
                           int retryAfterSeconds, boolean permanentFailure) {
            this.ok = ok;
            this.errorCode = errorCode;
            this.description = description;
            this.retryAfterSeconds = retryAfterSeconds;
            this.permanentFailure = permanentFailure;
        }

        static SendResult success() {
            return new SendResult(true, 0, null, 0, false);
        }

        static SendResult failure(int code, String description, int retryAfter) {
            // 400 covers "chat not found" / "message is too long"; 403 is the user blocking
            // the bot or deleting the chat. Neither improves by trying again later.
            boolean permanent = code == 400 || code == 403;
            return new SendResult(false, code, description, retryAfter, permanent);
        }
    }

    /**
     * Sends {@code html} to {@code chatId} using Telegram's HTML parse mode.
     *
     * <p>Callers must pass already-escaped text for anything user-supplied — use
     * {@link #escapeHtml(String)} on auction titles, usernames and the like, since those
     * are user input and an unescaped {@code <} breaks the message (or worse, the
     * formatting) for everyone.</p>
     *
     * @param chatId Telegram chat id as a string
     * @param html   message body, Telegram-flavoured HTML
     */
    public static SendResult sendMessage(String chatId, String html) {
        if (!TelegramConfig.isConfigured()) {
            return SendResult.failure(0, "Telegram is not configured", 0);
        }
        if (chatId == null || chatId.isBlank() || html == null || html.isBlank()) {
            return SendResult.failure(400, "Missing chat id or message body", 0);
        }

        String body = html.length() > MAX_MESSAGE_CHARS
                ? html.substring(0, MAX_MESSAGE_CHARS)
                : html;

        String form = "chat_id=" + url(chatId)
                + "&parse_mode=HTML"
                + "&disable_web_page_preview=true"
                + "&text=" + url(body);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + TelegramConfig.botToken() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body(), response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failure(0, "Interrupted while sending", 0);
        } catch (Exception e) {
            // The URL contains the bot token, so log the class of failure only.
            LOG.log(Level.WARNING, "Telegram sendMessage failed: {0}", e.getClass().getSimpleName());
            return SendResult.failure(0, "Transport failure", 0);
        }
    }

    private static SendResult parse(String payload, int statusCode) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (root.path("ok").asBoolean(false)) {
                return SendResult.success();
            }
            int code = root.path("error_code").asInt(statusCode);
            String description = root.path("description").asText("Telegram API error");
            int retryAfter = root.path("parameters").path("retry_after").asInt(0);
            return SendResult.failure(code, description, retryAfter);
        } catch (Exception e) {
            return SendResult.failure(statusCode, "Unreadable Telegram response", 0);
        }
    }

    /**
     * Escapes the three characters Telegram's HTML parse mode treats as markup. Apply to
     * every piece of user-controlled text interpolated into a message.
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;");  break;
                case '>': sb.append("&gt;");  break;
                default:  sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String url(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
