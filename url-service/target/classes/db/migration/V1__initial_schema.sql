-- V1__initial_schema.sql
-- Base schema: users + urls tables with all indexes

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Auto-update trigger for updated_at columns
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

-- ─── USERS ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                  BIGINT          PRIMARY KEY,
    email               VARCHAR(255)    NOT NULL UNIQUE,
    username            VARCHAR(50)     NOT NULL UNIQUE,
    password_hash       VARCHAR(255)    NOT NULL,
    tier                VARCHAR(20)     NOT NULL DEFAULT 'FREE'
                            CHECK (tier IN ('FREE','PRO','ENTERPRISE')),
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING_VERIFICATION'
                            CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DELETED')),
    email_verified      BOOLEAN         NOT NULL DEFAULT FALSE,
    two_factor_enabled  BOOLEAN         NOT NULL DEFAULT FALSE,
    two_factor_secret   TEXT,
    profile_image_url   VARCHAR(500),
    timezone            VARCHAR(50)     NOT NULL DEFAULT 'UTC',
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_login_at       TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_users_email    ON users(lower(email))    WHERE deleted_at IS NULL;
CREATE INDEX idx_users_username ON users(lower(username)) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_tier     ON users(tier, status)    WHERE deleted_at IS NULL;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ─── URLS ─────────────────────────────────────────────────────────────────────
CREATE TABLE urls (
    id              BIGINT          PRIMARY KEY,
    short_code      VARCHAR(10)     NOT NULL UNIQUE,
    original_url    TEXT            NOT NULL,
    user_id         BIGINT          REFERENCES users(id) ON DELETE SET NULL,
    title           VARCHAR(255),
    custom_alias    VARCHAR(50)     UNIQUE,
    expires_at      TIMESTAMPTZ,
    max_clicks      BIGINT,
    total_clicks    BIGINT          NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_private      BOOLEAN         NOT NULL DEFAULT FALSE,
    password_hash   TEXT,
    utm_source      VARCHAR(100),
    utm_medium      VARCHAR(100),
    utm_campaign    VARCHAR(100),
    utm_term        VARCHAR(100),
    utm_content     VARCHAR(100),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Performance-critical indexes
CREATE INDEX idx_urls_user_id   ON urls(user_id, created_at DESC) WHERE is_active = TRUE;
CREATE INDEX idx_urls_expires   ON urls(expires_at)  WHERE is_active = TRUE AND expires_at IS NOT NULL;
CREATE INDEX idx_urls_clicks    ON urls(total_clicks DESC) WHERE is_active = TRUE;
CREATE INDEX idx_urls_created   ON urls(created_at DESC);
-- Trigram index for search
CREATE INDEX idx_urls_url_trgm  ON urls USING GIN (original_url gin_trgm_ops);

CREATE TRIGGER trg_urls_updated_at
    BEFORE UPDATE ON urls
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
