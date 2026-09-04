CREATE TABLE IF NOT EXISTS specification_snapshots (
    id VARCHAR(255) PRIMARY KEY,
    test_run_id VARCHAR(255) NOT NULL,
    original_url VARCHAR(1024),
    resolved_spec_url VARCHAR(1024),
    openapi_version VARCHAR(50),
    base_url VARCHAR(1024),
    endpoints_count INT DEFAULT 0,
    spec_json CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_spec_snapshots_test_run FOREIGN KEY (test_run_id) REFERENCES test_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_spec_snapshots_run_id ON specification_snapshots(test_run_id);
