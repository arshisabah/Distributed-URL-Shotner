-- V4__audit_log.sql

CREATE TABLE audit_log (
    id          BIGINT       PRIMARY KEY,
    actor_id    BIGINT       REFERENCES users(id),
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id   VARCHAR(100),
    details     JSONB,
    ip_address  INET,
    user_agent  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_actor   ON audit_log(actor_id);
CREATE INDEX idx_audit_action  ON audit_log(action, created_at DESC);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);
