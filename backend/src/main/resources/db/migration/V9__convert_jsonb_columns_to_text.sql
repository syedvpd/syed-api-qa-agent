-- Migration V9: Convert legacy JSONB columns to TEXT
-- Aligns PostgreSQL column types with Spring Data JPA String entity mappings (@Column(columnDefinition = "TEXT"))

ALTER TABLE environments ALTER COLUMN custom_headers TYPE TEXT USING custom_headers::TEXT;

ALTER TABLE api_endpoints ALTER COLUMN tags TYPE TEXT USING tags::TEXT;
ALTER TABLE api_endpoints ALTER COLUMN parameters TYPE TEXT USING parameters::TEXT;
ALTER TABLE api_endpoints ALTER COLUMN request_body_schema TYPE TEXT USING request_body_schema::TEXT;
ALTER TABLE api_endpoints ALTER COLUMN response_schemas TYPE TEXT USING response_schemas::TEXT;
ALTER TABLE api_endpoints ALTER COLUMN security_requirements TYPE TEXT USING security_requirements::TEXT;

ALTER TABLE test_steps ALTER COLUMN request_headers TYPE TEXT USING request_headers::TEXT;
ALTER TABLE test_steps ALTER COLUMN request_body TYPE TEXT USING request_body::TEXT;

ALTER TABLE test_results ALTER COLUMN request_headers TYPE TEXT USING request_headers::TEXT;
ALTER TABLE test_results ALTER COLUMN response_headers TYPE TEXT USING response_headers::TEXT;

ALTER TABLE regression_findings ALTER COLUMN evidence TYPE TEXT USING evidence::TEXT;

ALTER TABLE reports ALTER COLUMN summary_json TYPE TEXT USING summary_json::TEXT;
