-- Initial Database Schema for Syed API QA Agent
-- V1__initial_schema.sql

CREATE TABLE projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    base_url VARCHAR(1024),
    openapi_url VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE environments (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    is_production BOOLEAN NOT NULL DEFAULT FALSE,
    auth_type VARCHAR(50),
    auth_credentials TEXT,
    custom_headers JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_runs (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) REFERENCES projects(id) ON DELETE SET NULL,
    environment_id VARCHAR(36) REFERENCES environments(id) ON DELETE SET NULL,
    openapi_url VARCHAR(1024) NOT NULL,
    target_base_url VARCHAR(1024),
    status VARCHAR(50) NOT NULL,
    environment_type VARCHAR(50) NOT NULL DEFAULT 'STAGING',
    total_endpoints INT NOT NULL DEFAULT 0,
    total_tests INT NOT NULL DEFAULT 0,
    passed_tests INT NOT NULL DEFAULT 0,
    failed_tests INT NOT NULL DEFAULT 0,
    warning_tests INT NOT NULL DEFAULT 0,
    blocked_tests INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_endpoints (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    operation_id VARCHAR(255),
    summary TEXT,
    description TEXT,
    tags JSONB,
    parameters JSONB,
    request_body_schema JSONB,
    response_schemas JSONB,
    security_requirements JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_cases (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    scenario_type VARCHAR(50) NOT NULL, -- e.g. CRUD_WORKFLOW, SINGLE_ENDPOINT, NEGATIVE
    status VARCHAR(50) NOT NULL,
    execution_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_steps (
    id VARCHAR(36) PRIMARY KEY,
    test_case_id VARCHAR(36) NOT NULL REFERENCES test_cases(id) ON DELETE CASCADE,
    api_endpoint_id VARCHAR(36) REFERENCES api_endpoints(id) ON DELETE SET NULL,
    step_order INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    method VARCHAR(10) NOT NULL,
    path_template VARCHAR(1024) NOT NULL,
    resolved_url VARCHAR(2048),
    request_headers JSONB,
    request_body JSONB,
    expected_status INT,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE dependencies (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    producer_endpoint_id VARCHAR(36) NOT NULL REFERENCES api_endpoints(id) ON DELETE CASCADE,
    consumer_endpoint_id VARCHAR(36) NOT NULL REFERENCES api_endpoints(id) ON DELETE CASCADE,
    parameter_name VARCHAR(255) NOT NULL,
    source_field VARCHAR(255) NOT NULL,
    confidence VARCHAR(20) NOT NULL, -- HIGH, MEDIUM, LOW
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE executions (
    id VARCHAR(36) PRIMARY KEY,
    test_step_id VARCHAR(36) NOT NULL REFERENCES test_steps(id) ON DELETE CASCADE,
    method VARCHAR(10) NOT NULL,
    request_url VARCHAR(2048) NOT NULL,
    request_headers JSONB,
    request_body TEXT,
    response_status INT,
    response_headers JSONB,
    response_body TEXT,
    latency_ms BIGINT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50) NOT NULL,
    error_type VARCHAR(100),
    error_details TEXT
);

CREATE TABLE assertion_results (
    id VARCHAR(36) PRIMARY KEY,
    execution_id VARCHAR(36) NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    assertion_type VARCHAR(50) NOT NULL, -- STATUS_CODE, SCHEMA, REQUIRED_FIELD, HEADER
    target_field VARCHAR(255),
    expected_value TEXT,
    actual_value TEXT,
    passed BOOLEAN NOT NULL,
    message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE captured_variables (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    execution_id VARCHAR(36) REFERENCES executions(id) ON DELETE SET NULL,
    variable_name VARCHAR(255) NOT NULL,
    variable_value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE failures (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    execution_id VARCHAR(36) REFERENCES executions(id) ON DELETE CASCADE,
    failure_type VARCHAR(100) NOT NULL,
    http_status INT,
    summary TEXT NOT NULL,
    probable_cause TEXT,
    evidence JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE performance_metrics (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    api_endpoint_id VARCHAR(36) REFERENCES api_endpoints(id) ON DELETE SET NULL,
    min_latency_ms BIGINT NOT NULL,
    max_latency_ms BIGINT NOT NULL,
    avg_latency_ms DOUBLE PRECISION NOT NULL,
    p50_latency_ms BIGINT NOT NULL,
    p90_latency_ms BIGINT NOT NULL,
    p95_latency_ms BIGINT NOT NULL,
    p99_latency_ms BIGINT NOT NULL,
    total_samples INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reports (
    id VARCHAR(36) PRIMARY KEY,
    test_run_id VARCHAR(36) NOT NULL UNIQUE REFERENCES test_runs(id) ON DELETE CASCADE,
    html_content TEXT,
    pdf_path VARCHAR(1024),
    summary_json JSONB,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX idx_test_runs_project ON test_runs(project_id);
CREATE INDEX idx_api_endpoints_run ON api_endpoints(test_run_id);
CREATE INDEX idx_test_cases_run ON test_cases(test_run_id);
CREATE INDEX idx_test_steps_case ON test_steps(test_case_id);
CREATE INDEX idx_executions_step ON executions(test_step_id);
CREATE INDEX idx_assertion_results_execution ON assertion_results(execution_id);
CREATE INDEX idx_captured_variables_run ON captured_variables(test_run_id);
CREATE INDEX idx_failures_run ON failures(test_run_id);
CREATE INDEX idx_performance_metrics_run ON performance_metrics(test_run_id);
