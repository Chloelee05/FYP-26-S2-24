-- Notification preferences (outbid / ending soon / auction won) per user.
-- Safe to re-run.
CREATE TABLE IF NOT EXISTS notification_preference(
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    out_bided BOOLEAN NOT NULL DEFAULT TRUE,
    ending_soon BOOLEAN NOT NULL DEFAULT TRUE,
    won_auction BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT user_id_preference FOREIGN KEY (user_id) REFERENCES users(id)
);

-- The upsert in NotificationDAO.saveUserPreferences uses ON CONFLICT (user_id),
-- which requires a unique index on user_id.
CREATE UNIQUE INDEX IF NOT EXISTS ux_notification_preference_user
    ON notification_preference (user_id);
