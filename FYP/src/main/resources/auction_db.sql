CREATE DATABASE auction_db
    WITH
    OWNER = postgres
    ENCODING = 'UTF8'
    LC_COLLATE = 'English_Singapore.1252'
    LC_CTYPE = 'English_Singapore.1252'
    LOCALE_PROVIDER = 'libc'
    TABLESPACE = pg_default
    CONNECTION LIMIT = -1
    IS_TEMPLATE = False;

COMMENT ON DATABASE auction_db
    IS 'FYP';

-- Drop tables in reverse dependency order
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS notification_preference;
DROP TABLE IF EXISTS payment_methods;
DROP TABLE IF EXISTS account_reports;
DROP TABLE IF EXISTS auction_tag_info;
DROP TABLE IF EXISTS auction_images;
DROP TABLE IF EXISTS auction_details;
DROP TABLE IF EXISTS bids;
DROP TABLE IF EXISTS auction;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS auction_status;
DROP TABLE IF EXISTS auction_type;
DROP TABLE IF EXISTS tags;
DROP TABLE IF EXISTS item_status;
DROP TABLE IF EXISTS user_reviews;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS user_status;
DROP TABLE IF EXISTS roles;

-- Roles
CREATE TABLE roles (
  id    SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  role  VARCHAR(50) NOT NULL
);

INSERT INTO roles (role) VALUES
  ('Admin'),
  ('Buyer'),
  ('Seller');

-- User status
CREATE TABLE user_status (
  id      SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status  VARCHAR(50) NOT NULL
);

-- IDs must match Status enum: ACTIVE(1), SUSPENDED(2), DELETED(3), PENDING(4), REJECTED(5)
INSERT INTO user_status (status) VALUES
  ('Active'),
  ('Suspended'),
  ('Deleted'),
  ('Pending'),
  ('Rejected');

-- Users
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

-- Auction status
CREATE TABLE auction_status (
  id      SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status  VARCHAR(50) NOT NULL
);

-- IDs must match AuctionStatus enum: ACTIVE(1), FINISHED(2), CANCELLED(3), PENDING(4)
INSERT INTO auction_status (status) VALUES
  ('Active'),
  ('Finished'),
  ('Cancelled'),
  ('Pending');

-- Auction type
CREATE TABLE auction_type (
  id    SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type  VARCHAR(50) NOT NULL
);

-- IDs must match AuctionType enum: PRICE_UP(1), DUTCH_AUCTION(2), BLIND(3)
INSERT INTO auction_type (type) VALUES
  ('Price Up'),
  ('Dutch Auction'),
  ('Blind');

-- Item status
CREATE TABLE item_status (
  id              SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  item_condition  VARCHAR(50) NOT NULL
);

-- IDs must match ItemCondition enum: BRAND_NEW(1), SLIGHTLY_USED(2), USED(3), DAMAGED(4)
INSERT INTO item_status (item_condition) VALUES
  ('Brand New'),
  ('Slightly Used'),
  ('Used'),
  ('Damaged');

-- Tags
CREATE TABLE tags (
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tag_name  VARCHAR(255) NOT NULL
);

-- Admin-managed categories (SCRUM-23)
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

-- Auction
CREATE TABLE auction (
  auction_id    BIGINT    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status_id     SMALLINT  NOT NULL,
  seller_id     BIGINT    NOT NULL,
  date_created  TIMESTAMP NOT NULL,
  date_end      TIMESTAMP NOT NULL,
  auction_type  SMALLINT  NOT NULL,
  report_count       INTEGER     NOT NULL DEFAULT 0, -- should be separate
  moderation_state   VARCHAR(20) NOT NULL DEFAULT 'active',
  cancel_reason      TEXT        DEFAULT NULL,
  CONSTRAINT auction_status_foreign FOREIGN KEY (status_id)    REFERENCES auction_status (id),
  CONSTRAINT auction_type_foreign   FOREIGN KEY (auction_type) REFERENCES auction_type   (id),
  CONSTRAINT seller_id_foreign      FOREIGN KEY (seller_id)    REFERENCES users           (id),
  CONSTRAINT auction_moderation_state_check CHECK (moderation_state IN ('active', 'flagged', 'removed'))
);

-- Auction details
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

-- Auction images
CREATE TABLE auction_images (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id   BIGINT    NOT NULL,
  image_url    TEXT      NOT NULL,
  upload_date  TIMESTAMP NOT NULL,
  CONSTRAINT auction_id_image_foreign FOREIGN KEY (auction_id) REFERENCES auction (auction_id)
);

-- Auction tag info
CREATE TABLE auction_tag_info (
  auction_id  BIGINT NOT NULL,
  tag_id      BIGINT NOT NULL,
  PRIMARY KEY (auction_id, tag_id),
  CONSTRAINT auction_id_info_foreign FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT tag_id_info_foreign     FOREIGN KEY (tag_id)     REFERENCES tags    (id)
);

-- Bids
CREATE TABLE bids (
  bid_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id  BIGINT    NOT NULL,
  user_id     BIGINT    NOT NULL,
  bid_amount  NUMERIC(10,2)    NOT NULL CHECK (bid_amount >= 0),
  bid_time    TIMESTAMP NOT NULL,
  CONSTRAINT auction_id_foreign FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT user_id_foreign    FOREIGN KEY (user_id)    REFERENCES users   (id)
);

-- Reviews between users (seller/buyer feedback); optional link to auction
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

--Account reports
CREATE TABLE account_reports (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id BIGINT NOT NULL,
    target_id   BIGINT NOT NULL,
    reason      TEXT   NOT NULL,
    comment     TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved    BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT user_id_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT user_id_target   FOREIGN KEY (target_id)   REFERENCES users (id),
    CONSTRAINT one_per_reporter_target UNIQUE (reporter_id, target_id)
);

CREATE TABLE notification_preference(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    out_bided BOOLEAN NOT NULL DEFAULT TRUE,
    ending_soon BOOLEAN NOT NULL DEFAULT TRUE,
    won_auction BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT user_id_preference FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Payment methods (credit cards). Full PAN AES-GCM encrypted; only brand + last4 in clear.
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
CREATE INDEX idx_payment_methods_user ON payment_methods (user_id);

-- In-app notifications (bidding results, account approval, Q&A, orders)
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
CREATE INDEX idx_notifications_user_unread ON notifications (user_id, is_read);

-- Orders (fund$ vs goods exchange — simulated checkout after a win)
CREATE TABLE orders (
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
CREATE INDEX idx_orders_buyer  ON orders (buyer_id);
CREATE INDEX idx_orders_seller ON orders (seller_id);

CREATE INDEX idx_bids_user    ON bids (user_id);
CREATE INDEX idx_bids_auction ON bids (auction_id);