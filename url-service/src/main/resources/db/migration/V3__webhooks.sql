-- V3__webhooks.sql

CREATE TABLE webhooks (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    secret      VARCHAR(100) NOT NULL,
    name        VARCHAR(100),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    events      TEXT[]       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webhooks_user ON webhooks(user_id) WHERE is_active = TRUE;

CREATE TRIGGER trg_webhooks_updated_at
    BEFORE UPDATE ON webhooks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
