-- Phase 4: User Ownership & Authorization
ALTER TABLE test_runs ADD COLUMN owner_id VARCHAR(128);
CREATE INDEX idx_test_runs_owner ON test_runs(owner_id);
