-- Migration: V7__phase6_run_control_and_scheduling.sql
-- Description: Phase 6 Run Control, Lifecycle Audit, and Scheduling Schema

-- 1. Alter test_runs table to support run control attributes
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(512);
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS timeout_seconds INTEGER DEFAULT 600;
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_test_runs_idempotency ON test_runs(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_test_runs_status ON test_runs(status);

-- 2. Lifecycle Audit Events Table
CREATE TABLE IF NOT EXISTS run_audit_events (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_run_audit_events_run ON run_audit_events(test_run_id);
CREATE INDEX IF NOT EXISTS idx_run_audit_events_created ON run_audit_events(created_at);

-- 3. Test Schedules Table
CREATE TABLE IF NOT EXISTS test_schedules (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(128),
    name VARCHAR(256) NOT NULL,
    openapi_url VARCHAR(2048) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    auth_type VARCHAR(32) DEFAULT 'NONE',
    auth_token VARCHAR(1024),
    schedule_type VARCHAR(32) NOT NULL,
    cron_expression VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_test_schedules_owner ON test_schedules(owner_id);
CREATE INDEX IF NOT EXISTS idx_test_schedules_enabled ON test_schedules(enabled);
