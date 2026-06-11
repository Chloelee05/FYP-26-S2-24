-- =============================================================================
-- AuctionHub — full schema for Supabase (PostgreSQL)
-- =============================================================================
-- Run in: Supabase Dashboard → SQL Editor → New query → paste all → Run
--
-- Do NOT run CREATE DATABASE — Supabase uses the default "postgres" database.
-- Safe for a NEW empty project. This script DROPs app tables first.
-- =============================================================================

-- ── 1. Drop app tables (dependency order) ─────────────────────────────────────
DROP TABLE IF EXISTS support_thread_reads CASCADE;
DROP TABLE IF EXISTS support_messages CASCADE;
DROP TABLE IF EXISTS support_threads CASCADE;
DROP TABLE IF EXISTS order_messages CASCADE;
DROP TABLE IF EXISTS auto_bids CASCADE;
DROP TABLE IF EXISTS auction_questions CASCADE;
DROP TABLE IF EXISTS watchlist CASCADE;
DROP TABLE IF EXISTS seller_reports CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS notifications CASCADE;
DROP TABLE IF EXISTS payment_methods CASCADE;
DROP TABLE IF EXISTS account_reports CASCADE;
DROP TABLE IF EXISTS auction_tag_info CASCADE;
DROP TABLE IF EXISTS auction_images CASCADE;
DROP TABLE IF EXISTS auction_details CASCADE;
DROP TABLE IF EXISTS bids CASCADE;
DROP TABLE IF EXISTS user_reviews CASCADE;
DROP TABLE IF EXISTS auction CASCADE;
DROP TABLE IF EXISTS categories CASCADE;
DROP TABLE IF EXISTS tags CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS auction_status CASCADE;
DROP TABLE IF EXISTS auction_type CASCADE;
DROP TABLE IF EXISTS item_status CASCADE;
DROP TABLE IF EXISTS user_status CASCADE;
DROP TABLE IF EXISTS roles CASCADE;

-- ── 2. Core schema (from auction_db.sql, without CREATE DATABASE) ─────────────

CREATE TABLE roles (
  id    SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  role  VARCHAR(50) NOT NULL
);
INSERT INTO roles (role) VALUES ('Admin'), ('Buyer'), ('Seller');

CREATE TABLE user_status (
  id      SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status  VARCHAR(50) NOT NULL
);
INSERT INTO user_status (status) VALUES
  ('Active'), ('Suspended'), ('Deleted'), ('Pending'), ('Rejected');

CREATE TABLE users (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         VARCHAR(255) NOT NULL UNIQUE,
  username      VARCHAR(255) NOT NULL UNIQUE,
  password      VARCHAR(255) NOT NULL,
  role_id       SMALLINT     NOT NULL,
  date_created  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_status_changed_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  status_id     SMALLINT     NOT NULL,
  two_factor_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
  two_factor_secret   TEXT,
  phone_encrypted     TEXT,
  address_encrypted   TEXT,
  profile_image_url   VARCHAR(512),
  CONSTRAINT user_role_id_foreign   FOREIGN KEY (role_id)   REFERENCES roles       (id),
  CONSTRAINT user_status_id_foreign FOREIGN KEY (status_id) REFERENCES user_status (id)
);

CREATE TABLE auction_status (
  id      SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status  VARCHAR(50) NOT NULL
);
INSERT INTO auction_status (status) VALUES
  ('Active'), ('Finished'), ('Cancelled'), ('Pending');

CREATE TABLE auction_type (
  id    SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type  VARCHAR(50) NOT NULL
);
INSERT INTO auction_type (type) VALUES
  ('Price Up'), ('Dutch Auction'), ('Blind');

CREATE TABLE item_status (
  id              SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  item_condition  VARCHAR(50) NOT NULL
);
INSERT INTO item_status (item_condition) VALUES
  ('Brand New'), ('Slightly Used'), ('Used'), ('Damaged');

CREATE TABLE tags (
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tag_name  VARCHAR(255) NOT NULL
);

CREATE TABLE categories (
  id            SERIAL        PRIMARY KEY,
  name          VARCHAR(100)  NOT NULL,
  description   TEXT,
  display_order INT           NOT NULL DEFAULT 0,
  slug          VARCHAR(120)  NOT NULL,
  is_deleted    BOOLEAN       NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT categories_name_unique UNIQUE (name),
  CONSTRAINT categories_slug_unique UNIQUE (slug)
);
INSERT INTO categories (name, slug, display_order) VALUES
  ('Electronics',    'electronics',    10),
  ('Fashion',        'fashion',        20),
  ('Home & Garden',  'home-garden',    30),
  ('Sports',         'sports',         40),
  ('Collectibles',   'collectibles',   50),
  ('Art',            'art',            60),
  ('Other',          'other',          99);

CREATE TABLE auction (
  auction_id         BIGINT    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status_id          SMALLINT  NOT NULL,
  seller_id          BIGINT    NOT NULL,
  date_created       TIMESTAMP NOT NULL,
  date_end           TIMESTAMP NOT NULL,
  auction_type       SMALLINT  NOT NULL,
  report_count       INTEGER   NOT NULL DEFAULT 0,
  moderation_state   VARCHAR(20) NOT NULL DEFAULT 'active',
  cancel_reason      TEXT        DEFAULT NULL,
  CONSTRAINT auction_status_foreign FOREIGN KEY (status_id)    REFERENCES auction_status (id),
  CONSTRAINT auction_type_foreign   FOREIGN KEY (auction_type) REFERENCES auction_type   (id),
  CONSTRAINT seller_id_foreign      FOREIGN KEY (seller_id)    REFERENCES users           (id),
  CONSTRAINT auction_moderation_state_check CHECK (moderation_state IN ('active', 'flagged', 'removed'))
);

CREATE TABLE auction_details (
  id                BIGINT         PRIMARY KEY,
  title             VARCHAR(255)   NOT NULL,
  description       TEXT           NOT NULL,
  category          VARCHAR(100)   NOT NULL DEFAULT 'Other',
  item_condition_id SMALLINT       NOT NULL,
  starting_price    NUMERIC(10,2)  NOT NULL DEFAULT 0 CHECK (starting_price >= 0),
  max_price         NUMERIC(10,2)  DEFAULT NULL CHECK (max_price IS NULL OR max_price > 0),
  quantity          INT            NOT NULL DEFAULT 1 CHECK (quantity >= 1),
  cost_price        NUMERIC(10,2)  DEFAULT NULL CHECK (cost_price IS NULL OR cost_price >= 0),
  dutch_floor_price NUMERIC(10,2)  DEFAULT NULL CHECK (dutch_floor_price IS NULL OR dutch_floor_price >= 0),
  winning_bid       INTEGER        DEFAULT NULL,
  winner_id         INTEGER        DEFAULT NULL,
  CONSTRAINT auction_id_details FOREIGN KEY (id) REFERENCES auction (auction_id),
  CONSTRAINT item_condition FOREIGN KEY (item_condition_id) REFERENCES item_status(id)
);

CREATE TABLE auction_images (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id   BIGINT    NOT NULL,
  image_url    TEXT      NOT NULL,
  upload_date  TIMESTAMP NOT NULL,
  CONSTRAINT auction_id_image_foreign FOREIGN KEY (auction_id) REFERENCES auction (auction_id)
);

CREATE TABLE auction_tag_info (
  auction_id  BIGINT NOT NULL,
  tag_id      BIGINT NOT NULL,
  PRIMARY KEY (auction_id, tag_id),
  CONSTRAINT auction_id_info_foreign FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT tag_id_info_foreign     FOREIGN KEY (tag_id)     REFERENCES tags    (id)
);

CREATE TABLE bids (
  bid_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id  BIGINT    NOT NULL,
  user_id     BIGINT    NOT NULL,
  bid_amount  NUMERIC(10,2) NOT NULL CHECK (bid_amount >= 0),
  bid_time    TIMESTAMP NOT NULL,
  CONSTRAINT auction_id_foreign FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT user_id_foreign    FOREIGN KEY (user_id)    REFERENCES users   (id)
);

CREATE TABLE user_reviews (
  id                 BIGSERIAL PRIMARY KEY,
  reviewer_user_id   BIGINT       NOT NULL,
  reviewee_user_id   BIGINT       NOT NULL,
  auction_id         BIGINT,
  rating             SMALLINT     NOT NULL CHECK (rating >= 1 AND rating <= 5),
  comment            TEXT,
  created_at         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT user_reviews_reviewer_fk  FOREIGN KEY (reviewer_user_id) REFERENCES users (id),
  CONSTRAINT user_reviews_reviewee_fk  FOREIGN KEY (reviewee_user_id) REFERENCES users (id),
  CONSTRAINT user_reviews_auction_fk   FOREIGN KEY (auction_id)       REFERENCES auction (auction_id),
  CONSTRAINT user_reviews_no_self      CHECK (reviewer_user_id <> reviewee_user_id),
  CONSTRAINT user_reviews_one_per_auction UNIQUE (auction_id, reviewer_user_id)
);

CREATE TABLE account_reports (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reporter_id BIGINT NOT NULL,
  target_id   BIGINT NOT NULL,
  reason      TEXT   NOT NULL,
  comment     TEXT,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved    BOOLEAN NOT NULL DEFAULT FALSE,
  admin_reply TEXT,
  CONSTRAINT user_id_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
  CONSTRAINT user_id_target   FOREIGN KEY (target_id)   REFERENCES users (id),
  CONSTRAINT one_per_reporter_target UNIQUE (reporter_id, target_id)
);

CREATE TABLE payment_methods (
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

CREATE TABLE notifications (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  type        VARCHAR(40) NOT NULL,
  message     TEXT        NOT NULL,
  link        VARCHAR(512),
  is_read     BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT notifications_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE orders (
  id                  BIGSERIAL    PRIMARY KEY,
  auction_id          BIGINT       NOT NULL,
  buyer_id            BIGINT       NOT NULL,
  seller_id           BIGINT       NOT NULL,
  amount              NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING_PAYMENT',
  payment_method_id   BIGINT,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at             TIMESTAMPTZ,
  completed_at        TIMESTAMPTZ,
  shipping_status     VARCHAR(30),
  shipping_updated_at TIMESTAMPTZ,
  refund_status       VARCHAR(20),
  refund_reason       TEXT,
  refund_requested_at TIMESTAMPTZ,
  refund_resolved_at  TIMESTAMPTZ,
  CONSTRAINT orders_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT orders_buyer_fk   FOREIGN KEY (buyer_id)   REFERENCES users   (id),
  CONSTRAINT orders_seller_fk  FOREIGN KEY (seller_id)  REFERENCES users   (id),
  CONSTRAINT orders_pm_fk      FOREIGN KEY (payment_method_id) REFERENCES payment_methods (id),
  CONSTRAINT orders_auction_unique UNIQUE (auction_id),
  CONSTRAINT orders_status_check CHECK (status IN ('PENDING_PAYMENT','PAID','COMPLETED','CANCELLED'))
);

-- ── 3. Tables from incremental migrations (not in base auction_db.sql) ────────

CREATE TABLE auto_bids (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id      BIGINT    NOT NULL,
  user_id         BIGINT    NOT NULL,
  max_amount_enc  TEXT      NOT NULL,
  note_enc        TEXT      DEFAULT NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT auto_bids_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT auto_bids_user_fk    FOREIGN KEY (user_id)    REFERENCES users   (id),
  CONSTRAINT auto_bids_unique     UNIQUE (auction_id, user_id)
);

CREATE TABLE auction_questions (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id      BIGINT      NOT NULL,
  asker_user_id   BIGINT      NOT NULL,
  question_text   TEXT        NOT NULL,
  answer_text     TEXT,
  answered_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT auction_questions_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT auction_questions_asker_fk FOREIGN KEY (asker_user_id) REFERENCES users (id)
);

CREATE TABLE watchlist (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  auction_id  BIGINT      NOT NULL,
  added_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT watchlist_user_fk    FOREIGN KEY (user_id)    REFERENCES users   (id),
  CONSTRAINT watchlist_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT watchlist_unique     UNIQUE (user_id, auction_id)
);

CREATE TABLE seller_reports (
  id                BIGSERIAL   PRIMARY KEY,
  reporter_user_id  BIGINT      NOT NULL,
  reported_user_id  BIGINT      NOT NULL,
  auction_id        BIGINT      NOT NULL,
  description       TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved          BOOLEAN     NOT NULL DEFAULT FALSE,
  admin_reply       TEXT,
  CONSTRAINT seller_reports_reporter_fk FOREIGN KEY (reporter_user_id) REFERENCES users   (id),
  CONSTRAINT seller_reports_reported_fk FOREIGN KEY (reported_user_id) REFERENCES users   (id),
  CONSTRAINT seller_reports_auction_fk  FOREIGN KEY (auction_id)       REFERENCES auction (auction_id),
  CONSTRAINT seller_reports_no_self     CHECK (reporter_user_id <> reported_user_id),
  CONSTRAINT seller_reports_one_per_auction UNIQUE (reporter_user_id, auction_id)
);

CREATE TABLE support_threads (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  subject     VARCHAR(255) NOT NULL DEFAULT 'Support request',
  status      VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT support_threads_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE support_messages (
  id              BIGSERIAL   PRIMARY KEY,
  thread_id       BIGINT      NOT NULL,
  sender_id       BIGINT      NOT NULL,
  body            TEXT        NOT NULL,
  attachment_url  TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT support_messages_thread_fk FOREIGN KEY (thread_id) REFERENCES support_threads (id),
  CONSTRAINT support_messages_sender_fk FOREIGN KEY (sender_id) REFERENCES users (id)
);

CREATE TABLE support_thread_reads (
  thread_id     BIGINT      NOT NULL,
  user_id       BIGINT      NOT NULL,
  last_read_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (thread_id, user_id),
  CONSTRAINT support_thread_reads_thread_fk FOREIGN KEY (thread_id) REFERENCES support_threads (id) ON DELETE CASCADE,
  CONSTRAINT support_thread_reads_user_fk  FOREIGN KEY (user_id)  REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE order_messages (
  id          BIGSERIAL   PRIMARY KEY,
  order_id    BIGINT      NOT NULL,
  sender_id   BIGINT      NOT NULL,
  body        TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT order_messages_order_fk  FOREIGN KEY (order_id)  REFERENCES orders (id),
  CONSTRAINT order_messages_sender_fk FOREIGN KEY (sender_id) REFERENCES users  (id)
);

-- ── 4. Indexes ───────────────────────────────────────────────────────────────

CREATE INDEX idx_payment_methods_user ON payment_methods (user_id);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id, is_read);
CREATE INDEX idx_orders_buyer  ON orders (buyer_id);
CREATE INDEX idx_orders_seller ON orders (seller_id);
CREATE INDEX idx_bids_user    ON bids (user_id);
CREATE INDEX idx_bids_auction ON bids (auction_id);
CREATE INDEX idx_auto_bids_auction ON auto_bids (auction_id);
CREATE INDEX idx_auction_questions_auction ON auction_questions (auction_id, created_at);
CREATE INDEX idx_support_threads_user ON support_threads (user_id);
CREATE INDEX idx_support_threads_status ON support_threads (status);
CREATE INDEX idx_support_messages_thread ON support_messages (thread_id);
CREATE INDEX idx_support_thread_reads_user ON support_thread_reads (user_id);
CREATE INDEX idx_order_messages_order ON order_messages (order_id);
CREATE INDEX idx_auction_details_title ON auction_details (LOWER(title));
CREATE INDEX idx_auction_moderation_end ON auction (moderation_state, date_end);

-- Done. Optional: run demo_seed.sql separately for test data.
