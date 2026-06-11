-- FR4.3 browse history + commission / featured listing revenue (business model)

CREATE TABLE IF NOT EXISTS browse_history (
    id          BIGSERIAL PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auction_id  BIGINT  NOT NULL REFERENCES auction(auction_id) ON DELETE CASCADE,
    viewed_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_browse_history_user ON browse_history(user_id);
CREATE INDEX IF NOT EXISTS idx_browse_history_auction ON browse_history(auction_id);
CREATE INDEX IF NOT EXISTS idx_browse_history_viewed_at ON browse_history(viewed_at DESC);

ALTER TABLE auction ADD COLUMN IF NOT EXISTS is_featured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS featured_until TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS platform_revenue (
    id          BIGSERIAL PRIMARY KEY,
    revenue_type VARCHAR(30) NOT NULL,
    order_id    BIGINT REFERENCES orders(id) ON DELETE SET NULL,
    auction_id  BIGINT REFERENCES auction(auction_id) ON DELETE SET NULL,
    seller_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount      NUMERIC(12, 2) NOT NULL,
    rate_pct    NUMERIC(5, 2),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_platform_revenue_order_type UNIQUE (order_id, revenue_type)
);

CREATE INDEX IF NOT EXISTS idx_platform_revenue_type ON platform_revenue(revenue_type);
CREATE INDEX IF NOT EXISTS idx_platform_revenue_created ON platform_revenue(created_at DESC);
