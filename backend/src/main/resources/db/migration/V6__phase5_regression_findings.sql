-- Migration: V6__phase5_regression_findings.sql
-- Description: Phase 5 Regression Intelligence findings schema

CREATE TABLE IF NOT EXISTS regression_findings (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    baseline_run_id VARCHAR(36) REFERENCES test_runs(id) ON DELETE SET NULL,
    finding_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    endpoint_path VARCHAR(512),
    http_method VARCHAR(16),
    baseline_value VARCHAR(256),
    current_value VARCHAR(256),
    description TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_regression_findings_run ON regression_findings(test_run_id);
CREATE INDEX IF NOT EXISTS idx_regression_findings_baseline ON regression_findings(baseline_run_id);
CREATE INDEX IF NOT EXISTS idx_regression_findings_severity ON regression_findings(severity);
