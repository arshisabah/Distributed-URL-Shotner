-- V5__domain_blocklist.sql

CREATE TABLE domain_blocklist (
    id         BIGINT       PRIMARY KEY,
    domain     VARCHAR(255) NOT NULL UNIQUE,
    reason     VARCHAR(500),
    added_by   BIGINT       REFERENCES users(id),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_blocklist_domain ON domain_blocklist(domain) WHERE is_active = TRUE;

-- Seed with a few known bad domains for demonstration
INSERT INTO domain_blocklist (id, domain, reason) VALUES
  (1, 'malware-example.com', 'Known malware distribution'),
  (2, 'phishing-example.net', 'Known phishing site');
