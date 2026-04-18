-- Online Auction Platform Database Schema
-- Product ver. 0.0

CREATE DATABASE IF NOT EXISTS auction_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE auction_db;

-- ============================================================
-- Users table
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    email       VARCHAR(100) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    full_name   VARCHAR(100),
    address     VARCHAR(255),
    phone       VARCHAR(20),
    role        ENUM('BUYER', 'SELLER', 'ADMIN') NOT NULL DEFAULT 'BUYER',
    status      ENUM('ACTIVE', 'SUSPENDED', 'PENDING') NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ============================================================
-- Categories table
-- ============================================================
CREATE TABLE IF NOT EXISTS categories (
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
) ENGINE=InnoDB;

-- ============================================================
-- Products table
-- ============================================================
CREATE TABLE IF NOT EXISTS products (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    seller_id   INT          NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    image_url   VARCHAR(500),
    category_id INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (seller_id)   REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ============================================================
-- Auctions table
-- ============================================================
CREATE TABLE IF NOT EXISTS auctions (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    product_id    INT            NOT NULL,
    start_price   DECIMAL(12,2)  NOT NULL,
    current_price DECIMAL(12,2)  NOT NULL,
    bid_increment DECIMAL(12,2)  NOT NULL DEFAULT 1.00,
    start_time    DATETIME       NOT NULL,
    end_time      DATETIME       NOT NULL,
    status        ENUM('ACTIVE', 'ENDED', 'CANCELLED') NOT NULL DEFAULT 'ACTIVE',
    strategy      ENUM('PRICE_UP', 'LOW_START_HIGH', 'PUBLIC_BIDDING') NOT NULL DEFAULT 'PRICE_UP',
    winner_id     INT DEFAULT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    FOREIGN KEY (winner_id)  REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ============================================================
-- Bids table
-- ============================================================
CREATE TABLE IF NOT EXISTS bids (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    auction_id INT           NOT NULL,
    buyer_id   INT           NOT NULL,
    amount     DECIMAL(12,2) NOT NULL,
    bid_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE,
    FOREIGN KEY (buyer_id)   REFERENCES users(id)    ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================
-- Seed data
-- ============================================================

-- Default admin account (password: admin123)
INSERT INTO users (username, email, password, full_name, role, status) VALUES
('admin', 'admin@auction.com', '$2a$10$WCCkv4KViXlAovqQi.rgM.91lokWcN9jkeE4jfY8Aw/tqPIxh6XPy', 'System Admin', 'ADMIN', 'ACTIVE');

-- Sample categories
INSERT INTO categories (name) VALUES
('Electronics'),
('Fashion'),
('Home & Garden'),
('Sports'),
('Books'),
('Collectibles'),
('Vehicles'),
('Other');
