-- Flyway Migration V5: Create event_log table
-- This migration creates the schema for storing audit events and system logs

-- Create event_log table
CREATE TABLE event_log (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant          VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255),
    event_type      VARCHAR(100) NOT NULL,
    description     TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Unique constraint: UUID is globally unique
    CONSTRAINT uq_event_log_uuid UNIQUE (uuid)
);

-- Create index for tenant lookups
CREATE INDEX idx_event_log_tenant ON event_log(tenant);

-- Create index for tenant+user lookups
CREATE INDEX idx_event_log_tenant_user ON event_log(tenant, user_id);

-- Create index for event type lookups
CREATE INDEX idx_event_log_event_type ON event_log(event_type);

-- Create index for created_at for time-based queries
CREATE INDEX idx_event_log_created_at ON event_log(created_at DESC);

-- Create composite index for common query pattern (tenant + time range)
CREATE INDEX idx_event_log_tenant_created_at ON event_log(tenant, created_at DESC);

-- GIN index for JSONB metadata searches
CREATE INDEX idx_event_log_metadata ON event_log USING GIN(metadata);

-- Comments for documentation
COMMENT ON TABLE event_log IS 'Stores audit events and system logs';
COMMENT ON COLUMN event_log.id IS 'Auto-incrementing primary key';
COMMENT ON COLUMN event_log.uuid IS 'Event UUID (globally unique)';
COMMENT ON COLUMN event_log.tenant IS 'Tenant identifier for multi-tenancy support';
COMMENT ON COLUMN event_log.user_id IS 'User identifier (nullable for system events)';
COMMENT ON COLUMN event_log.event_type IS 'Event type (e.g., TOOL_EXECUTED, MCP_REQUEST_PROCESSED, etc.)';
COMMENT ON COLUMN event_log.description IS 'Human-readable event description';
COMMENT ON COLUMN event_log.metadata IS 'Extensible JSONB field for event-specific structured data';
COMMENT ON COLUMN event_log.created_at IS 'Timestamp when the event occurred';
