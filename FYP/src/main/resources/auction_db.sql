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
DROP TABLE IF EXISTS auction_tag_info;
DROP TABLE IF EXISTS auction_images;
DROP TABLE IF EXISTS auction_details;
DROP TABLE IF EXISTS bids;
DROP TABLE IF EXISTS auction;
DROP TABLE IF EXISTS auction_status;
DROP TABLE IF EXISTS auction_type;
DROP TABLE IF EXISTS tags;
DROP TABLE IF EXISTS item_status;
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

INSERT INTO user_status (status) VALUES
  ('Active'),
  ('Suspended'),
  ('Deleted');

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

-- Auction type
CREATE TABLE auction_type (
  id    SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type  VARCHAR(50) NOT NULL
);

-- Item status
CREATE TABLE item_status (
  id              SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  item_condition  VARCHAR(50) NOT NULL
);

-- Tags
CREATE TABLE tags (
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tag_name  VARCHAR(255) NOT NULL
);

-- Auction
CREATE TABLE auction (
  auction_id    BIGINT    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status_id     SMALLINT  NOT NULL,
  seller_id     BIGINT    NOT NULL,
  date_created  TIMESTAMP NOT NULL,
  date_end      TIMESTAMP NOT NULL,
  auction_type  SMALLINT  NOT NULL,
  report_count       INTEGER     NOT NULL DEFAULT 0,
  moderation_state   VARCHAR(20) NOT NULL DEFAULT 'active',
  CONSTRAINT auction_status_foreign FOREIGN KEY (status_id)    REFERENCES auction_status (id),
  CONSTRAINT auction_type_foreign   FOREIGN KEY (auction_type) REFERENCES auction_type   (id),
  CONSTRAINT seller_id_foreign      FOREIGN KEY (seller_id)    REFERENCES users           (id),
  CONSTRAINT auction_moderation_state_check CHECK (moderation_state IN ('active', 'flagged', 'removed'))
);

-- Auction details
CREATE TABLE auction_details (
  id                BIGINT       PRIMARY KEY,
  title             VARCHAR(255) NOT NULL,
  description       TEXT         NOT NULL,
  category          VARCHAR(100) NOT NULL DEFAULT 'Other',
  item_condition_id SMALLINT     NOT NULL,
  winning_bid       INTEGER      DEFAULT NULL,
  winner_id         INTEGER      DEFAULT NULL,
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