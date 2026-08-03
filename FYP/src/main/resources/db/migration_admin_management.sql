-- Admin database management (Stakeholder #3b): a product/service discriminator on
-- listings, and an audit trail for the management operations an admin can now perform.
--
-- Safe to re-run: every statement is guarded by IF NOT EXISTS or a catalogue lookup.

-- ── Listings are products or services ────────────────────────────────────────
-- The minimum requirements name "products, services, customers, auction transactions",
-- but the platform only ever modelled physical goods. A discriminator on the existing
-- listing is the honest minimum: a service is auctioned, paid for and reviewed exactly
-- like a product, so a parallel entity hierarchy would duplicate the whole auction tree
-- to express one difference. Admin can read, filter and correct this field, and it flows
-- through the moderation table, the analytics report and the admin reports.
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS listing_kind VARCHAR(10) NOT NULL DEFAULT 'PRODUCT';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'auction_details_listing_kind_check'
          AND conrelid = 'auction_details'::regclass
    ) THEN
        ALTER TABLE auction_details
            ADD CONSTRAINT auction_details_listing_kind_check
            CHECK (listing_kind IN ('PRODUCT', 'SERVICE'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_auction_details_listing_kind
    ON auction_details (listing_kind);

-- ── Admin management audit trail ─────────────────────────────────────────────
-- "Manage database" only stays defensible if every write an admin makes is traceable:
-- editing a listing's title or correcting an order state changes a record a seller or
-- buyer owns, so the previous value is kept alongside who changed it and why.
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id     BIGINT      NOT NULL REFERENCES users (id),
    entity_type  VARCHAR(20) NOT NULL,
    entity_id    BIGINT      NOT NULL,
    action       VARCHAR(40) NOT NULL,
    field_name   VARCHAR(40),
    old_value    TEXT,
    new_value    TEXT,
    reason       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_entity
    ON admin_audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_created
    ON admin_audit_log (created_at DESC);
