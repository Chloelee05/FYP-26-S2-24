-- SCRUM-40: optional Buy It Now price on ascending auctions.
-- NULL = not offered. When set, buyers may purchase immediately at this price.
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS buy_it_now_price NUMERIC(10,2) DEFAULT NULL
        CHECK (buy_it_now_price IS NULL OR buy_it_now_price > 0);
