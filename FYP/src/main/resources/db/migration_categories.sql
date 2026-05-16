-- SCRUM-23: Admin-managed categories table
-- Apply once against auction_db. Safe to re-run (IF NOT EXISTS / ON CONFLICT).

CREATE TABLE IF NOT EXISTS categories (
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

-- Seed with the values that already appear in the homepage/navbar hardcoded filters
-- so existing auction_details.category strings resolve correctly.
INSERT INTO categories (name, slug, display_order) VALUES
    ('Electronics',    'electronics',    10),
    ('Fashion',        'fashion',        20),
    ('Home & Garden',  'home-garden',    30),
    ('Sports',         'sports',         40),
    ('Collectibles',   'collectibles',   50),
    ('Art',            'art',            60),
    ('Other',          'other',          99)
ON CONFLICT (name) DO NOTHING;
