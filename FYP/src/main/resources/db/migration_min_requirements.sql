-- Minimum-requirements completion migration.
-- Adds: pending/rejected user statuses, seller item fields (quantity/cost_price),
-- Dutch-auction floor price, and the payment_methods / notifications / orders tables.
-- Safe to re-run.

-- ── User status: Pending (4), Rejected (5) ────────────────────────────────────
-- IDs must match Status enum: ACTIVE(1), SUSPENDED(2), DELETED(3), PENDING(4), REJECTED(5)
INSERT INTO user_status (status)
SELECT 'Pending'  WHERE NOT EXISTS (SELECT 1 FROM user_status WHERE status = 'Pending');
INSERT INTO user_status (status)
SELECT 'Rejected' WHERE NOT EXISTS (SELECT 1 FROM user_status WHERE status = 'Rejected');

-- ── Seller item fields + Dutch floor price ────────────────────────────────────
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS quantity INT NOT NULL DEFAULT 1
        CHECK (quantity >= 1);

-- Seller's internal cost/purchase price. Private: never exposed to buyers.
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS cost_price NUMERIC(10,2) DEFAULT NULL
        CHECK (cost_price IS NULL OR cost_price >= 0);

-- Dutch auctions: clock descends from starting_price down to this floor.
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS dutch_floor_price NUMERIC(10,2) DEFAULT NULL
        CHECK (dutch_floor_price IS NULL OR dutch_floor_price >= 0);

-- ── Payment methods (Buyer/Seller account detail: credit card) ────────────────
-- Full PAN stored AES-GCM encrypted (card_number_enc); only brand + last4 in clear.
CREATE TABLE IF NOT EXISTS payment_methods (
  id              BIGSERIAL   PRIMARY KEY,
  user_id         BIGINT      NOT NULL,
  card_holder     VARCHAR(255) NOT NULL,
  card_brand      VARCHAR(20),
  card_last4      VARCHAR(4)  NOT NULL,
  exp_month       SMALLINT    NOT NULL CHECK (exp_month BETWEEN 1 AND 12),
  exp_year        SMALLINT    NOT NULL,
  card_number_enc TEXT        NOT NULL,
  is_default      BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT payment_methods_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX IF NOT EXISTS idx_payment_methods_user ON payment_methods (user_id);

-- ── Notifications (bidding results, account approval, Q&A, orders) ─────────────
CREATE TABLE IF NOT EXISTS notifications (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  type        VARCHAR(40) NOT NULL,
  message     TEXT        NOT NULL,
  link        VARCHAR(512),
  is_read     BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT notifications_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications (user_id, is_read);

-- ── Orders (fund$ vs goods exchange — simulated checkout) ──────────────────────
CREATE TABLE IF NOT EXISTS orders (
  id                BIGSERIAL    PRIMARY KEY,
  auction_id        BIGINT       NOT NULL,
  buyer_id          BIGINT       NOT NULL,
  seller_id         BIGINT       NOT NULL,
  amount            NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING_PAYMENT',
  payment_method_id BIGINT,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at           TIMESTAMPTZ,
  completed_at      TIMESTAMPTZ,
  CONSTRAINT orders_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT orders_buyer_fk   FOREIGN KEY (buyer_id)   REFERENCES users   (id),
  CONSTRAINT orders_seller_fk  FOREIGN KEY (seller_id)  REFERENCES users   (id),
  CONSTRAINT orders_pm_fk      FOREIGN KEY (payment_method_id) REFERENCES payment_methods (id),
  CONSTRAINT orders_auction_unique UNIQUE (auction_id),
  CONSTRAINT orders_status_check CHECK (status IN ('PENDING_PAYMENT','PAID','COMPLETED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_orders_buyer  ON orders (buyer_id);
CREATE INDEX IF NOT EXISTS idx_orders_seller ON orders (seller_id);

-- ── Recommendation / query-perf indexes ──────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_bids_user    ON bids (user_id);
CREATE INDEX IF NOT EXISTS idx_bids_auction ON bids (auction_id);
