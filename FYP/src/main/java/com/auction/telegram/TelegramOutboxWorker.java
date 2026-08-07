package com.auction.telegram;

import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.TelegramOutboxDAO;
import com.auction.dao.TelegramOutboxDAO.PendingMessage;
import com.auction.util.SecurityUtil;

import java.util.List;
import java.util.logging.Logger;

/**
 * Drains {@code telegram_outbox} one batch at a time. Scheduled by
 * {@link com.auction.listener.TelegramOutboxListener}; kept separate from it so the
 * delivery rules can be unit-tested without a servlet container.
 *
 * <h2>Pacing</h2>
 * <p>Telegram allows roughly one message per second to a given chat and about thirty per
 * second overall. A {@value #PER_SEND_PAUSE_MS}ms gap between sends satisfies the per-chat
 * limit even in the worst case where every row in a batch belongs to the same user, and
 * leaves the global limit an order of magnitude away. {@value #BATCH_SIZE} rows per pass
 * therefore takes about twenty seconds — comfortably inside the lease
 * {@link TelegramOutboxDAO#claimDue(int)} takes out, and cheap on a 0.5-CPU instance
 * because the thread is asleep for almost all of it.</p>
 *
 * <h2>Failure handling</h2>
 * <ul>
 *   <li><b>403, or 400 "chat not found"</b> — the user blocked the bot or the chat is gone.
 *       Retrying can never help, so the link is deactivated (they will not receive anything
 *       else until they reconnect) and the row is {@code SKIPPED}.</li>
 *   <li><b>429</b> — flood control. The row's {@code next_attempt_at} moves out by
 *       {@code parameters.retry_after} and its attempt count is left alone, because our
 *       pacing being too fast is not the message's fault.</li>
 *   <li><b>Other 4xx</b> — malformed and unfixable by repetition; {@code SKIPPED}.</li>
 *   <li><b>5xx and transport errors</b> — retried on the DAO's backoff ladder, then
 *       {@code FAILED} with the last error recorded.</li>
 * </ul>
 *
 * <p>When no bot is configured the worker returns immediately without touching the
 * database, exactly as notification email does when SMTP is unset: the feature is simply
 * absent in a local checkout rather than being a source of errors.</p>
 */
public class TelegramOutboxWorker {

    private static final Logger LOG = Logger.getLogger(TelegramOutboxWorker.class.getName());

    /** Messages drained per pass. */
    static final int BATCH_SIZE = 20;

    /** Gap between sends, satisfying Telegram's ~1 message/second/chat limit. */
    static final long PER_SEND_PAUSE_MS = 1_100;

    private final TelegramOutboxDAO outboxDAO;
    private final TelegramLinkDAO linkDAO;

    public TelegramOutboxWorker() {
        this(new TelegramOutboxDAO(), new TelegramLinkDAO());
    }

    /** Injection constructor for testing. */
    public TelegramOutboxWorker(TelegramOutboxDAO outboxDAO, TelegramLinkDAO linkDAO) {
        this.outboxDAO = outboxDAO;
        this.linkDAO = linkDAO;
    }

    /**
     * Delivers up to one batch.
     *
     * @return the number of messages successfully sent
     */
    public int runOnce() {
        if (!TelegramConfig.isConfigured()) {
            return 0;
        }

        List<PendingMessage> batch;
        try {
            batch = outboxDAO.claimDue(BATCH_SIZE);
        } catch (RuntimeException e) {
            // An un-migrated or briefly unreachable database must not kill the scheduler.
            LOG.fine("Telegram outbox claim failed: " + e.getMessage());
            return 0;
        }

        int sent = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0 && !pause()) {
                break; // shutting down
            }
            if (deliver(batch.get(i))) {
                sent++;
            }
        }
        return sent;
    }

    /** @return true when the message was accepted by Telegram */
    boolean deliver(PendingMessage message) {
        String chatId;
        try {
            TelegramLinkDAO.LinkInfo link = linkDAO.findByUserId(message.userId);
            if (link == null) {
                // Unlinked between enqueue and send, or the link was just deactivated.
                outboxDAO.markSkipped(message.id, "no active Telegram link");
                return false;
            }
            // Chat ids are held encrypted rather than in the clear, so the plaintext exists
            // only for the moment it takes to make the API call.
            chatId = SecurityUtil.decrypt(link.chatIdEncrypted);
        } catch (RuntimeException e) {
            outboxDAO.markFailed(message.id, message.attempts, "link lookup failed");
            return false;
        }

        TelegramClient.SendResult result = TelegramClient.sendMessage(chatId, message.body);
        if (result.ok) {
            outboxDAO.markSent(message.id);
            return true;
        }

        if (result.errorCode == 429) {
            outboxDAO.delayFor(message.id, result.retryAfterSeconds);
            return false;
        }

        if (chatIsGone(result)) {
            // Stop sending to a chat Telegram says no longer accepts us, rather than
            // burning a retry budget every time a notification fires.
            try {
                linkDAO.unlinkUser(message.userId);
            } catch (RuntimeException e) {
                LOG.fine("Could not deactivate Telegram link: " + e.getMessage());
            }
            outboxDAO.markSkipped(message.id, describe(result));
            return false;
        }

        if (result.permanentFailure) {
            outboxDAO.markSkipped(message.id, describe(result));
            return false;
        }

        outboxDAO.markFailed(message.id, message.attempts, describe(result));
        return false;
    }

    /**
     * Whether Telegram is telling us this chat will never accept a message again: a 403
     * (blocked, or the account is deactivated) or the specific 400 for a chat that no longer
     * exists. A generic 400 is a bad request on our side and must not cost the user a link.
     */
    static boolean chatIsGone(TelegramClient.SendResult result) {
        if (result.errorCode == 403) {
            return true;
        }
        if (result.errorCode != 400 || result.description == null) {
            return false;
        }
        String d = result.description.toLowerCase();
        return d.contains("chat not found") || d.contains("chat_id is empty");
    }

    /** Error codes and descriptions are safe to store; the bot token never appears in them. */
    private static String describe(TelegramClient.SendResult result) {
        String description = result.description == null ? "unknown error" : result.description;
        return result.errorCode > 0 ? result.errorCode + " " + description : description;
    }

    /** @return false when the thread was interrupted, i.e. the context is shutting down */
    private static boolean pause() {
        try {
            Thread.sleep(PER_SEND_PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
