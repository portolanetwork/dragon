-- Flyway Migration V4: Add transport_type column to mcp_server table
-- This migration adds transport type field

ALTER TABLE mcp_server
ADD COLUMN transport_type TEXT DEFAULT 'streaming_http' CHECK (transport_type IN ('streaming_http'));

-- Comments for documentation
COMMENT ON COLUMN mcp_server.transport_type IS 'Transport type: streaming_http (HTTP streaming transport)';
