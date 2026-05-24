-- Buyer watchlist (SCRUM-XX). Run after auction_db.sql.
-- One entry per buyer per auction; UNIQUE is enforced at both DB and DAO layer.
CREATE TABLE watchlist (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  auction_id  BIGINT      NOT NULL,
  added_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT watchlist_user_fk    FOREIGN KEY (user_id)    REFERENCES users   (id),
  CONSTRAINT watchlist_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT watchlist_unique     UNIQUE (user_id, auction_id)
);
