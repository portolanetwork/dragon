-- Flyway Migration V3: Add auth_type and static_token columns to mcp_server table
-- This migration adds authentication type and static token fields

ALTER TABLE mcp_server
ADD COLUMN auth_type TEXT DEFAULT 'none' CHECK (auth_type IN ('none', 'discover', 'static_auth_header')),
ADD COLUMN static_token TEXT;

-- Comments for documentation
COMMENT ON COLUMN mcp_server.auth_type IS 'Authentication type: none (no auth), discover (OAuth discovery), or static_auth_header (static token header)';
COMMENT ON COLUMN mcp_server.static_token IS 'Static authentication token for static_auth_header auth type (optional)';
