CREATE TABLE iview_device_acceptance_run (
    id VARCHAR(36) PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES iview_device(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    planned_end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    interval_ms INTEGER NOT NULL,
    minimum_success_rate DECIMAL(5,4) NOT NULL,
    failure_limit INTEGER NOT NULL,
    attempts BIGINT NOT NULL DEFAULT 0,
    successes BIGINT NOT NULL DEFAULT 0,
    failures BIGINT NOT NULL DEFAULT 0,
    recoveries BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    max_duration_ms BIGINT NOT NULL DEFAULT 0,
    max_consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NOT NULL DEFAULT ''
);

CREATE INDEX idx_iview_acceptance_device_started ON iview_device_acceptance_run(device_id, started_at);
