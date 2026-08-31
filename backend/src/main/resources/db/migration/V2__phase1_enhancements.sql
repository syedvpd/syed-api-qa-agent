-- Migration V2: Phase 1 Enhancements

ALTER TABLE dependencies ADD COLUMN IF NOT EXISTS reason TEXT;
ALTER TABLE test_steps ADD COLUMN IF NOT EXISTS failure_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_dependencies_confidence ON dependencies(confidence);
CREATE INDEX IF NOT EXISTS idx_test_steps_status ON test_steps(status);
