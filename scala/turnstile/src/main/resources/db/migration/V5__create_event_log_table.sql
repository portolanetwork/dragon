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
    source_type     VARCHAR(100),
    source_uuid     UUID,
    raw_data        JSONB NOT NULL DEFAULT 'null'::jsonb,
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

-- Create index for source_type lookups
CREATE INDEX idx_event_log_source_type ON event_log(source_type);

-- Create index for source_uuid lookups
CREATE INDEX idx_event_log_source_uuid ON event_log(source_uuid);

-- Create composite index for source lookups
CREATE INDEX idx_event_log_source_type_uuid ON event_log(source_type, source_uuid);

-- Create index for created_at for time-based queries
CREATE INDEX idx_event_log_created_at ON event_log(created_at DESC);

-- Create composite index for common query pattern (tenant + time range)
CREATE INDEX idx_event_log_tenant_created_at ON event_log(tenant, created_at DESC);

-- GIN index for JSONB raw_data searches
CREATE INDEX idx_event_log_raw_data ON event_log USING GIN(raw_data);

-- Comments for documentation
COMMENT ON TABLE event_log IS 'Stores audit events and system logs';
COMMENT ON COLUMN event_log.id IS 'Auto-incrementing primary key';
COMMENT ON COLUMN event_log.uuid IS 'Event UUID (globally unique)';
COMMENT ON COLUMN event_log.tenant IS 'Tenant identifier for multi-tenancy support';
COMMENT ON COLUMN event_log.user_id IS 'User identifier (nullable for system events)';
COMMENT ON COLUMN event_log.event_type IS 'Event type (e.g., fetch_tools, get, post, login, client_connected, login_failed)';
COMMENT ON COLUMN event_log.description IS 'Human-readable event description';
COMMENT ON COLUMN event_log.source_type IS 'Source type (e.g., mcp_server, system, client) for event origin classification';
COMMENT ON COLUMN event_log.source_uuid IS 'UUID reference to the source entity (no FK constraint for flexibility)';
COMMENT ON COLUMN event_log.raw_data IS 'Extensible JSONB field for event-specific data';
COMMENT ON COLUMN event_log.created_at IS 'Timestamp when the event occurred';
