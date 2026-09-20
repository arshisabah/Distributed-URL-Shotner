-- V2__api_keys_and_refresh_tokens.sql

CREATE TABLE api_keys (
    id                  BIGINT       PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    key_prefix          VARCHAR(20)  NOT NULL,
    key_hash            CHAR(64)     NOT NULL UNIQUE,
    rate_limit_per_min  INTEGER,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    allowed_ips         JSONB,
    expires_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    total_requests      BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_hash ON api_keys(key_hash)  WHERE is_active = TRUE;
CREATE INDEX idx_api_keys_user ON api_keys(user_id);

CREATE TABLE api_key_scopes (
    api_key_id  BIGINT      NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    scope       VARCHAR(50) NOT NULL,
    PRIMARY KEY (api_key_id, scope)
);

CREATE TABLE refresh_tokens (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  CHAR(64)     NOT NULL UNIQUE,
    device_info VARCHAR(500),
    ip_address  INET,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_hash ON refresh_tokens(token_hash) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id)    WHERE revoked_at IS NULL;
