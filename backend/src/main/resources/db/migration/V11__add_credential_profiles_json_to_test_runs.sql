-- V11: Add credential_profiles_json to test_runs for multi-identity pipeline integration
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS credential_profiles_json TEXT;
