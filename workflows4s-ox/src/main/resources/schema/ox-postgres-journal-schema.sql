-- Workflow event journal table for PostgresWorkflowStorage
-- Stores all workflow events in chronological order for event sourcing

CREATE TABLE IF NOT EXISTS workflow_journal (
    event_id    SERIAL PRIMARY KEY,                          -- Auto-incrementing event ID (preserves order)
    template_id TEXT      NOT NULL,                          -- Workflow template/type identifier
    instance_id TEXT      NOT NULL,                          -- Workflow instance identifier
    event_data  BYTEA     NOT NULL,                          -- Serialized event data
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP -- Event creation timestamp
);

-- Composite index for efficient event retrieval by workflow instance
CREATE INDEX IF NOT EXISTS idx_workflow_id ON workflow_journal (template_id, instance_id);

-- Comments for documentation
COMMENT ON TABLE workflow_journal IS
    'Event journal for workflow event sourcing. Each row represents a single event in a workflow instance history.';
COMMENT ON COLUMN workflow_journal.event_id IS
    'Auto-incrementing ID that preserves event ordering within the journal';
COMMENT ON COLUMN workflow_journal.template_id IS
    'Workflow template identifier (e.g., "order-processing", "user-onboarding")';
COMMENT ON COLUMN workflow_journal.instance_id IS
    'Unique instance identifier for a specific workflow execution';
COMMENT ON COLUMN workflow_journal.event_data IS
    'Serialized event data (format determined by ByteCodec implementation)';
