-- ═══════════════════════════════════════════════════════════════
-- Mini Banking Journey Orchestrator - Database Schema
-- Evam-style Audit Trail & Analytics
-- ═══════════════════════════════════════════════════════════════

-- Events table: Immutable audit trail of all customer events
CREATE TABLE IF NOT EXISTS events (
    event_id    VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(64)  NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    timestamp   TIMESTAMP    NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- Composite index: customer timeline queries
CREATE INDEX IF NOT EXISTS idx_events_customer_time
    ON events (customer_id, timestamp DESC);

-- Single index: analytics by event type
CREATE INDEX IF NOT EXISTS idx_events_event_type
    ON events (event_type);

-- Actions table: Audit trail of all outbound actions
CREATE TABLE IF NOT EXISTS actions (
    action_id   VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(64)  NOT NULL,
    action_type VARCHAR(32)  NOT NULL,
    message     TEXT,
    channel     VARCHAR(32),
    sent_at     TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- Index: customer action history
CREATE INDEX IF NOT EXISTS idx_actions_customer
    ON actions (customer_id);

-- Journey states table: Backup/analytics snapshot of journey states
CREATE TABLE IF NOT EXISTS journey_states (
    customer_id    VARCHAR(64)  PRIMARY KEY,
    current_step   VARCHAR(32),
    started_at     TIMESTAMP,
    document_count INT          DEFAULT 0,
    state_json     JSONB,
    updated_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
