-- Shared with url-service; Flyway will skip if tables already exist
-- Use baseline-on-migrate=true in both services pointing to the same DB
-- In production, run migrations from one service (url-service) only

-- No-op if already created by url-service
SELECT 1;
