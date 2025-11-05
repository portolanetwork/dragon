-- Flyway Migration V1: Create mcp_servers table
-- This migration creates the initial schema for storing user-registered MCP servers

-- Create mcp_servers table
CREATE TABLE mcp_server (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant          VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    url             TEXT NOT NULL,
    client_id       VARCHAR(255),
    client_secret   TEXT,
    refresh_token   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Unique constraint: UUID is globally unique across entire table
    CONSTRAINT uq_mcp_servers_uuid UNIQUE (uuid),

    -- Unique constraint: Each tenant+user can have only one server with a given name
    CONSTRAINT uq_mcp_servers_tenant_user_name UNIQUE (tenant, user_id, name)
);

-- Create index for fast tenant lookups
CREATE INDEX idx_mcp_servers_tenant ON mcp_server(tenant);

-- Create index for tenant+user lookups
CREATE INDEX idx_mcp_servers_tenant_user ON mcp_server(tenant, user_id);

-- Create index for uuid lookups (covered by unique constraint but explicit for clarity)
CREATE INDEX idx_mcp_servers_uuid ON mcp_server(uuid);

-- Comments for documentation
COMMENT ON TABLE mcp_server IS 'Stores user-registered MCP servers';
COMMENT ON COLUMN mcp_server.id IS 'Auto-incrementing primary key';
COMMENT ON COLUMN mcp_server.tenant IS 'Tenant identifier for multi-tenancy support';
COMMENT ON COLUMN mcp_server.user_id IS 'User identifier (can be username, email, or UUID)';
COMMENT ON COLUMN mcp_server.uuid IS 'Server UUID (globally unique across entire table)';
COMMENT ON COLUMN mcp_server.name IS 'Server name (unique per tenant+user combination)';
COMMENT ON COLUMN mcp_server.url IS 'Server URL';
COMMENT ON COLUMN mcp_server.client_id IS 'OAuth client ID for authentication (optional)';
COMMENT ON COLUMN mcp_server.client_secret IS 'OAuth client secret for authentication (optional)';
COMMENT ON COLUMN mcp_server.refresh_token IS 'OAuth refresh token for token renewal (optional)';
COMMENT ON COLUMN mcp_server.created_at IS 'Timestamp when the server was first registered';
COMMENT ON COLUMN mcp_server.updated_at IS 'Timestamp when the server was last updated';
