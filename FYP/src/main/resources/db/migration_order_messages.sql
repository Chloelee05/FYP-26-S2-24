-- Direct buyer <-> seller messaging tied to an order, plus seller refund decision tracking.
-- Safe to re-run.

CREATE TABLE IF NOT EXISTS order_messages (
  id          BIGSERIAL   PRIMARY KEY,
  order_id    BIGINT      NOT NULL,
  sender_id   BIGINT      NOT NULL,
  body        TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT order_messages_order_fk  FOREIGN KEY (order_id)  REFERENCES orders (id),
  CONSTRAINT order_messages_sender_fk FOREIGN KEY (sender_id) REFERENCES users  (id)
);
CREATE INDEX IF NOT EXISTS idx_order_messages_order ON order_messages (order_id);

-- When the seller approves/declines a refund request.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS refund_resolved_at TIMESTAMPTZ;
