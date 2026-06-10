-- User account reports (admin moderation). Safe to re-run.
CREATE TABLE IF NOT EXISTS account_reports (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id BIGINT NOT NULL,
    target_id   BIGINT NOT NULL,
    reason      TEXT   NOT NULL,
    comment     TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved    BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT user_id_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT user_id_target   FOREIGN KEY (target_id)   REFERENCES users (id),
    CONSTRAINT one_per_reporter_target UNIQUE (reporter_id, target_id)
);

ALTER TABLE account_reports ADD COLUMN IF NOT EXISTS resolved BOOLEAN NOT NULL DEFAULT FALSE;
