-- Flyway Migration V2: Add token_endpoint column to mcp_server table
-- This migration adds the OAuth token endpoint URL column

ALTER TABLE mcp_server
ADD COLUMN token_endpoint TEXT;

-- Comment for documentation
COMMENT ON COLUMN mcp_server.token_endpoint IS 'OAuth token endpoint URL (optional)';
