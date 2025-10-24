-- Flyway Migration V1: Create user_tools table
-- This migration creates the initial schema for storing user-specific custom tools

-- Create user_tools table
CREATE TABLE user_tools (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    tool_name       VARCHAR(255) NOT NULL,
    description     TEXT NOT NULL,
    schema_json     JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Unique constraint: Each user can have only one tool with a given name
    CONSTRAINT uq_user_tools_user_tool UNIQUE (user_id, tool_name)
);

-- Create index for fast user lookups
CREATE INDEX idx_user_tools_user_id ON user_tools(user_id);

-- Create index for user+tool lookups (covered by unique constraint but explicit for clarity)
CREATE INDEX idx_user_tools_user_tool ON user_tools(user_id, tool_name);

-- Create GIN index for JSONB queries (enables efficient JSON querying)
CREATE INDEX idx_user_tools_schema_json ON user_tools USING GIN (schema_json);

-- Comments for documentation
COMMENT ON TABLE user_tools IS 'Stores user-specific custom MCP tools with JSON schemas';
COMMENT ON COLUMN user_tools.id IS 'Auto-incrementing primary key';
COMMENT ON COLUMN user_tools.user_id IS 'User identifier (can be username, email, or UUID)';
COMMENT ON COLUMN user_tools.tool_name IS 'Unique tool name within a user scope';
COMMENT ON COLUMN user_tools.description IS 'Human-readable tool description';
COMMENT ON COLUMN user_tools.schema_json IS 'JSONB schema defining tool parameters (supports efficient querying)';
COMMENT ON COLUMN user_tools.created_at IS 'Timestamp when the tool was first created';
COMMENT ON COLUMN user_tools.updated_at IS 'Timestamp when the tool was last updated';
