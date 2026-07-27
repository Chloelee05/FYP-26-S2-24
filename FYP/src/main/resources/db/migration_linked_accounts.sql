-- Third-party (OAuth) login links per user (SCRUM-17). Safe to re-run.
-- A user can link at most one account per provider, and a provider account
-- can only be linked to one user.
CREATE TABLE IF NOT EXISTS linked_accounts (
  id             BIGSERIAL    PRIMARY KEY,
  user_id        BIGINT       NOT NULL,
  provider       VARCHAR(20)  NOT NULL,
  provider_uid   VARCHAR(255) NOT NULL,
  provider_email VARCHAR(255),
  linked_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT linked_accounts_user_fk       FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT linked_accounts_user_provider UNIQUE (user_id, provider),
  CONSTRAINT linked_accounts_provider_uid  UNIQUE (provider, provider_uid)
);
