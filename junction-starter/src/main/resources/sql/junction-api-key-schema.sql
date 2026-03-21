CREATE TABLE IF NOT EXISTS junction_api_keys (
    id VARCHAR(128) PRIMARY KEY,
    key_hash VARCHAR(128) NOT NULL,
    key_prefix VARCHAR(32) NOT NULL,
    name VARCHAR(255),
    description TEXT,
    tier VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    allowed_models_json TEXT NOT NULL,
    allowed_ips_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT,
    last_used_at BIGINT,
    request_count BIGINT NOT NULL,
    created_by VARCHAR(255),
    metadata_json TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_junction_api_keys_key_hash
    ON junction_api_keys (key_hash);

CREATE UNIQUE INDEX IF NOT EXISTS idx_junction_api_keys_key_prefix
    ON junction_api_keys (key_prefix);

CREATE INDEX IF NOT EXISTS idx_junction_api_keys_status
    ON junction_api_keys (status);

CREATE INDEX IF NOT EXISTS idx_junction_api_keys_tier
    ON junction_api_keys (tier);
