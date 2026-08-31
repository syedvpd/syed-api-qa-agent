-- Phase 3 Migration: Performance Benchmarking & Historical Regression
ALTER TABLE test_runs ADD COLUMN baseline_run_id VARCHAR(36);
ALTER TABLE test_runs ADD COLUMN regression_summary_json TEXT;

CREATE INDEX idx_perf_metrics_run ON performance_metrics(test_run_id);
CREATE INDEX idx_perf_metrics_endpoint ON performance_metrics(api_endpoint_id);
