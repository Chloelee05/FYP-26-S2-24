-- Telegram notification channel: account linking, delivery outbox and the
-- per-event preferences that go with it. Additive and safe to re-run.
--
-- Privacy note (PDPA): the Telegram chat id is personal data, so it is never
-- stored in the clear. Two derived forms are kept for two different jobs:
--   chat_id_hash      SHA-256 of (chat id + AUCTION_TELEGRAM_PEPPER) — deterministic,
--                     so an incoming webhook can find the owning account.
--   chat_id_encrypted SecurityUtil.encrypt(...) — AES-256-GCM with a random IV, so it
--                     is reversible when a message has to be sent but not searchable.
-- linked_at doubles as the consent timestamp: the user only reaches the link step
-- after accepting the notice in the connect dialog.

-- ---------------------------------------------------------------------------
-- Linked Telegram chats
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS telegram_links (
    id                BIGSERIAL   PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    chat_id_hash      CHAR(64)    NOT NULL,
    chat_id_encrypted TEXT        NOT NULL,
    telegram_username VARCHAR(64),
    linked_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    unlinked_at       TIMESTAMPTZ,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT telegram_links_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);

-- Partial unique indexes rather than plain UNIQUE constraints: history rows stay in
-- place after an unlink, while the database still guarantees "at most one active link
-- per user" and "at most one active link per Telegram chat". That is what makes
-- re-linking and the two-accounts-one-phone case safe without application locking.
CREATE UNIQUE INDEX IF NOT EXISTS ux_telegram_links_active_user
    ON telegram_links (user_id) WHERE active;
CREATE UNIQUE INDEX IF NOT EXISTS ux_telegram_links_active_chat
    ON telegram_links (chat_id_hash) WHERE active;

-- ---------------------------------------------------------------------------
-- One-time linking codes (deep-link token and manual OTP share this table)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS telegram_link_codes (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    code_hash  CHAR(64)    NOT NULL,
    kind       VARCHAR(10) NOT NULL DEFAULT 'OTP',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    attempts   INT         NOT NULL DEFAULT 0,
    CONSTRAINT telegram_link_codes_user_fk FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT telegram_link_codes_code_uq  UNIQUE (code_hash),
    CONSTRAINT telegram_link_codes_kind_chk CHECK (kind IN ('OTP', 'DEEPLINK'))
);

CREATE INDEX IF NOT EXISTS ix_telegram_link_codes_user
    ON telegram_link_codes (user_id, used_at);

-- ---------------------------------------------------------------------------
-- Delivery outbox (written by the app, drained by the background worker)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS telegram_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    auction_id      BIGINT,
    body            TEXT         NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at         TIMESTAMPTZ,
    last_error      TEXT,
    dedupe_key      VARCHAR(120),
    CONSTRAINT telegram_outbox_user_fk    FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT telegram_outbox_status_chk CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
);

-- Collapses duplicate enqueues of the same event while one is still waiting, without
-- blocking a legitimate repeat after the first has been delivered.
CREATE UNIQUE INDEX IF NOT EXISTS ux_telegram_outbox_dedupe
    ON telegram_outbox (dedupe_key)
    WHERE status = 'PENDING' AND dedupe_key IS NOT NULL;

-- The worker's only hot query: due pending rows, oldest first.
CREATE INDEX IF NOT EXISTS ix_telegram_outbox_due
    ON telegram_outbox (next_attempt_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Per-event Telegram preferences (additive to notification_preference)
-- ---------------------------------------------------------------------------
-- telegram_enabled is the master switch. Everything defaults on except the seller
-- price-change feed, which is the only high-volume event and is therefore opt-in.
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_enabled       BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_outbid        BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_won           BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_lost          BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_seller_result BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_seller_price  BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- Admin-editable copy for the connect dialog and the bot's own replies
-- ---------------------------------------------------------------------------
-- Explanatory and consent wording only. Button labels and validation messages stay
-- in code: they are UI mechanics, not content an administrator should be rewording.
-- Same column shape as migration_landing_content.sql, so AdminLandingContent renders
-- these automatically with no React change.
INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('telegram.connect.heading', 'Telegram', 'Connect dialog — heading', FALSE, 500,
        'Get auction alerts on Telegram'),
    ('telegram.connect.body', 'Telegram', 'Connect dialog — introduction', TRUE, 510,
        'Link your Telegram account and AuctionHub will message you the moment something happens on a listing you care about — no need to keep the site open.'),
    ('telegram.connect.events', 'Telegram', 'Connect dialog — what you''ll receive', TRUE, 520,
        'You were outbid on an auction' || chr(10) ||
        'You won an auction' || chr(10) ||
        'An auction you bid on closed without you' || chr(10) ||
        'Your listing sold or ended (sellers)'),
    ('telegram.connect.privacy', 'Telegram', 'Connect dialog — PDPA consent notice', TRUE, 530,
        'We store your Telegram chat ID so we can send you these messages. It is encrypted at rest and is never shown to other members or used for marketing. Connecting is your consent to this use; disconnect at any time from Account settings and we stop immediately.'),
    ('telegram.bot.welcome', 'Telegram', 'Bot reply — /start with no code', TRUE, 540,
        'Hello! This bot delivers AuctionHub auction alerts.' || chr(10) || chr(10) ||
        'To connect, open Account settings on AuctionHub, choose Connect Telegram, and either tap the link there or send me the 6-digit code it shows you.'),
    ('telegram.bot.linked', 'Telegram', 'Bot reply — link succeeded', TRUE, 550,
        'You''re connected. I''ll message you here when you are outbid, when you win, and when your listings close.' || chr(10) || chr(10) ||
        'Send /status to check this link, or /unlink to stop the messages.'),
    ('telegram.bot.invalidCode', 'Telegram', 'Bot reply — code wrong or expired', TRUE, 560,
        'That code isn''t valid — it may have expired or already been used. Open Account settings on AuctionHub and start the connection again to get a fresh one.'),
    ('telegram.bot.unlinked', 'Telegram', 'Bot reply — link removed', TRUE, 570,
        'This Telegram account is no longer linked to AuctionHub and will not receive further alerts. You can reconnect any time from Account settings.'),
    ('telegram.bot.moved', 'Telegram', 'Bot reply — link moved to another chat', TRUE, 580,
        'Heads up: this AuctionHub account has just been connected to a different Telegram account, so alerts will no longer arrive here. If that wasn''t you, sign in to AuctionHub and change your password.'),
    ('telegram.bot.help', 'Telegram', 'Bot reply — /help', TRUE, 590,
        'Commands I understand:' || chr(10) ||
        '/status — show whether this chat is linked' || chr(10) ||
        '/unlink — stop receiving AuctionHub alerts here' || chr(10) ||
        '/help — show this message' || chr(10) || chr(10) ||
        'You can also just send me the 6-digit code from AuctionHub to connect.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
