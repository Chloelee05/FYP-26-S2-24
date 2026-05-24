-- Seller reports (SCRUM-52 buyer-report feature). Run after auction_db.sql.
-- One report per buyer per auction; seller identity is stored from DB, never from request.
CREATE TABLE seller_reports (
  id                BIGSERIAL   PRIMARY KEY,
  reporter_user_id  BIGINT      NOT NULL,
  reported_user_id  BIGINT      NOT NULL,
  auction_id        BIGINT      NOT NULL,
  description       TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT seller_reports_reporter_fk     FOREIGN KEY (reporter_user_id) REFERENCES users   (id),
  CONSTRAINT seller_reports_reported_fk     FOREIGN KEY (reported_user_id) REFERENCES users   (id),
  CONSTRAINT seller_reports_auction_fk      FOREIGN KEY (auction_id)       REFERENCES auction (auction_id),
  CONSTRAINT seller_reports_no_self         CHECK (reporter_user_id <> reported_user_id),
  CONSTRAINT seller_reports_one_per_auction UNIQUE (reporter_user_id, auction_id)
);
