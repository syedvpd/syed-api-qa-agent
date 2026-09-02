-- V10: User Credentials and Multi-Tenant Identity Verification
CREATE TABLE IF NOT EXISTS user_credentials (
    user_id VARCHAR(128) PRIMARY KEY,
    secret_hash VARCHAR(256) NOT NULL,
    role VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_credentials_role ON user_credentials(role);
