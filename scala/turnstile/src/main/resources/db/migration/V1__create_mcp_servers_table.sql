-- Flyway Migration V1: Create mcp_servers table
-- This migration creates the initial schema for storing user-registered MCP servers

-- Create mcp_servers table
CREATE TABLE mcp_servers (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    uuid            VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    url             TEXT NOT NULL,
    client_id       VARCHAR(255),
    client_secret   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Unique constraint: Each user can have only one server with a given UUID
    CONSTRAINT uq_mcp_servers_user_uuid UNIQUE (user_id, uuid)
);

-- Create index for fast user lookups
CREATE INDEX idx_mcp_servers_user_id ON mcp_servers(user_id);

-- Create index for user+uuid lookups (covered by unique constraint but explicit for clarity)
CREATE INDEX idx_mcp_servers_user_uuid ON mcp_servers(user_id, uuid);

-- Comments for documentation
COMMENT ON TABLE mcp_servers IS 'Stores user-registered MCP servers';
COMMENT ON COLUMN mcp_servers.id IS 'Auto-incrementing primary key';
COMMENT ON COLUMN mcp_servers.user_id IS 'User identifier (can be username, email, or UUID)';
COMMENT ON COLUMN mcp_servers.uuid IS 'Server UUID (unique within a user scope)';
COMMENT ON COLUMN mcp_servers.name IS 'Server name';
COMMENT ON COLUMN mcp_servers.url IS 'Server URL';
COMMENT ON COLUMN mcp_servers.client_id IS 'OAuth client ID for authentication (optional)';
COMMENT ON COLUMN mcp_servers.client_secret IS 'OAuth client secret for authentication (optional)';
COMMENT ON COLUMN mcp_servers.created_at IS 'Timestamp when the server was first registered';
COMMENT ON COLUMN mcp_servers.updated_at IS 'Timestamp when the server was last updated';
