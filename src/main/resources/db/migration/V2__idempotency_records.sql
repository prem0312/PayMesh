-- V2__idempotency_records.sql
-- Authoritative, persistent idempotency store with primary key duplicate detection

CREATE TABLE idempotency_records (
    packet_hash VARCHAR(64) PRIMARY KEY,
    packet_id VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    bridge_node_id VARCHAR(100),
    hop_count INT NOT NULL DEFAULT 0,
    transaction_id BIGINT,
    failure_reason TEXT,
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_idempotency_claimed_at ON idempotency_records (claimed_at);
CREATE INDEX idx_idempotency_status ON idempotency_records (status);
