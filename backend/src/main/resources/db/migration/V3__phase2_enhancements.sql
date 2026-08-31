-- V3__phase2_enhancements.sql
-- Syed API QA Agent: Phase 2 Schema Additions for Negative Testing, Dynamic Auth, and Teardown

-- 1. Track created resources for automated reverse-dependency cleanup
CREATE TABLE IF NOT EXISTS cleanup_records (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(256) NOT NULL,
    delete_endpoint VARCHAR(512) NOT NULL,
    execution_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    cleaned_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_cleanup_records_run ON cleanup_records(test_run_id);
CREATE INDEX IF NOT EXISTS idx_cleanup_records_status ON cleanup_records(status);

-- 2. Categorize test cases (POSITIVE_CRUD, NEGATIVE_VALIDATION, BOUNDARY_LIMITS, MALFORMED_INPUT, SECURITY_PROBE, AUTH_FLOW, CLEANUP_TEARDOWN)
ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS category VARCHAR(32) DEFAULT 'POSITIVE_CRUD';

-- 3. Add dynamic authentication configuration & cleanup status to test_runs
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS auth_login_url VARCHAR(512);
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS auth_login_payload TEXT;
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS auth_token_path VARCHAR(128);
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS auth_refresh_url VARCHAR(512);
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS cleanup_status VARCHAR(32) DEFAULT 'NOT_RUN';
