-- SCRUM-52: Auto-bid (proxy bidding) table.
-- max_amount_enc and note_enc are stored as AES-GCM ciphertexts (SecurityUtil.encrypt).
-- Plaintext values are never persisted; decryption happens exclusively in AutoBidDAO.
CREATE TABLE IF NOT EXISTS auto_bids (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    auction_id      BIGINT    NOT NULL,
    user_id         BIGINT    NOT NULL,
    -- AES-256-GCM ciphertext of the buyer's max bid amount (plaintext is a NUMERIC string).
    -- SCRUM-296: encrypt at rest because this reveals the buyer's maximum bidding strategy.
    max_amount_enc  TEXT      NOT NULL,
    -- Optional private note encrypted at rest (SCRUM-296).
    note_enc        TEXT      DEFAULT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auto_bids_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
    CONSTRAINT auto_bids_user_fk    FOREIGN KEY (user_id)    REFERENCES users   (id),
    -- One active auto-bid per buyer per auction; re-setting replaces the old one.
    CONSTRAINT auto_bids_unique     UNIQUE (auction_id, user_id)
);

-- Speed up lookups by auction (used in processAutoBids hot path).
CREATE INDEX IF NOT EXISTS idx_auto_bids_auction ON auto_bids (auction_id);
