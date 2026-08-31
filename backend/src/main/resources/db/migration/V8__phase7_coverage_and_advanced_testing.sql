-- Migration: V8__phase7_coverage_and_advanced_testing.sql
-- Description: Phase 7 API QA Coverage, Behavior Classification, and Advanced Contract Assertions

-- 1. Alter test_runs table with coverage metrics
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS coverage_score DECIMAL(5,2);
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS coverage_summary_json TEXT;

-- 2. Endpoint Behavior Classification Table
CREATE TABLE IF NOT EXISTS endpoint_coverage (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    method VARCHAR(16) NOT NULL,
    path VARCHAR(2048) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    reason TEXT,
    crud_tested BOOLEAN DEFAULT FALSE,
    negative_tested BOOLEAN DEFAULT FALSE,
    contract_validated BOOLEAN DEFAULT FALSE,
    assertions_count INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_endpoint_coverage_run ON endpoint_coverage(test_run_id);
CREATE INDEX IF NOT EXISTS idx_endpoint_coverage_class ON endpoint_coverage(classification);
